(ns leiningen.source-deps
  (:require [mranderson.util :refer :all]
            [mranderson.move :as move]
            [cemerick.pomegranate.aether :as aether]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [clojure.pprint :as pp]
            [com.climate.claypoole :as cp]
            [leiningen.core.main :refer [info debug]]
            [clojure.edn :as edn])
  (:import (clojure.lang PersistentQueue)
           [java.util.zip ZipFile ZipEntry ZipOutputStream]
           [java.util UUID]
           (org.apache.commons.lang3 RegExUtils StringUtils)))

(defn- zip-target-file
  [target-dir entry-path]
  (let [entry-path (str/replace-first (str entry-path) #"^/" "")]
    (fs/file target-dir entry-path)))

(defn- unzip
  "Takes the path to a zipfile source and unzips it to target-dir."
  ([source]
   (unzip source (name source)))
  ([source target-dir]
   (let [zip (ZipFile. (fs/file source))
         entries (enumeration-seq (.entries zip))]
     (doseq [^ZipEntry entry entries
             :when (not (.isDirectory entry))
             :let [f (zip-target-file target-dir entry)]]
       (fs/mkdirs (fs/parent f))
       (io/copy (.getInputStream zip entry) f))
     (->> entries
          (into [] (comp (keep (fn [^ZipEntry entry]
                                 (when-not (.isDirectory entry)
                                   (.getName entry))))
                         (filter (fn [^String entry]
                                   (or (.endsWith entry ".clj")
                                       (.endsWith entry ".cljc")
                                       (.endsWith entry ".cljs"))))))))))

(defn- cljfile->prefix [clj-file]
  (->> (str/split clj-file #"/")
       butlast
       (str/join ".")))

(defn- possible-prefixes [clj-files]
  (->> clj-files
       (map cljfile->prefix)
       (remove #(str/blank? %))
       (remove #(= "clojure.core" %))
       (reduce #(if (%1 %2) (assoc %1 %2 (inc (%1 %2))) (assoc %1 %2 1) ) {})
       (filter #(< 1 (val %)))
       (map first)
       (map #(str/replace % "_" "-"))))

(defn- replacement-prefix [pprefix src src-path art-name art-version underscorize?]
  (let [path (->> (str/split (str src-path) #"/")
                  (drop-while #(not= src %))
                  rest
                  (concat [pprefix])
                  (map #(if underscorize? % (str/replace % "_" "-"))))]
    (->> [art-name art-version]
         (concat path)
         (str/join "."))))

(defn- replacement [prefix postfix underscorize?]
  (->> (if underscorize?
         (-> postfix
             str
             (str/replace "-" "_"))
         postfix)
       vector
       (concat [prefix])
       (str/join "." )
       symbol))

(defn- update-path-in-file [file old-path new-path]
  (let [old (slurp file)
        new (str/replace old old-path new-path)]
    (when-not (= old new)
      (spit file new))))

(defn- update-file [file prefixes]
  (let [old (slurp file)
        new (reduce (fn [^String source [prefix new-prefix]]
                      (let [pattern1 (re-pattern (str "(\\[\\s*)" prefix "(\\s+\\[?)"))
                            pattern2 (re-pattern (str "(\\s+\\(?)" prefix "([\\s\\.])"))
                            replacement (str "$1" new-prefix "$2")]
                        (-> source
                            (RegExUtils/replaceAll pattern1 replacement)
                            (RegExUtils/replaceAll pattern2 replacement))))
                    old
                    prefixes)]
    (when-not (= old new)
      (spit file new))))

(defn- update-deftypes [file old-ns new-deftype]
  (let [^String old (slurp file)
        old-deftype-prefix (-> old-ns name (str/replace "-" "_"))
        pattern (re-pattern (str "([\\s\\^]+)" old-deftype-prefix))
        replacement (str "$1" (name new-deftype))
        new (RegExUtils/replaceAll old pattern replacement)]
    (when-not (= old new)
      (spit file new))))

(defn- import-fragment-left [^String clj-source]
  (let [index-of-import (.indexOf clj-source ":import")]
    (when (> index-of-import -1)
      (drop (loop [ns-decl-fragment (reverse (take index-of-import clj-source))
                   index-of-open-bracket 1]
              (cond (= \( (first ns-decl-fragment))
                    (- index-of-import index-of-open-bracket)

                    (not (re-matches #"\s" (-> ns-decl-fragment first str)))
                    (count clj-source)

                    :default (recur (rest ns-decl-fragment) (inc index-of-open-bracket)))) clj-source))))

(defn- import-fragment [clj-source]
  (let [import-fragment-left (import-fragment-left clj-source)]
    (when (and import-fragment-left (> (count import-fragment-left) 0))
      (loop [index 1
             open-close 0]
        (if (> open-close 0)
          (apply str (take index import-fragment-left))
          (recur (inc index) (cond (= \( (nth import-fragment-left index))
                                   (dec open-close)

                                   (= \) (nth import-fragment-left index))
                                   (inc open-close)

                                   :default open-close)))))))

(defn- garble-import! [file-prefix file import-fragment uuid]
  (let [old (slurp (fs/file file-prefix file))
        new (if import-fragment
              (str/replace old import-fragment (str "\"" uuid "\""))
              old)]
    (when-not (= old new)
      (spit (fs/file file-prefix file) new))))

(defn- retrieve-import [file-prefix file]
  (let [cont (slurp (fs/file file-prefix file))
        import-fragment (import-fragment cont)]
    (when-not (str/blank? import-fragment)
      [file import-fragment])))

(defn- find-orig-import [imports file]
  (or (loop [imps imports]
        (let [imp (first imps)]
          (if (.endsWith (str file) (str (first imp)))
            (second imp)
            (recur (rest imps))))) ""))

(defn- degarble-imports! [imports file uuid]
  (let [old (slurp file)
        orig-import (find-orig-import imports file)
        new (str/replace old (str "\"" uuid "\"") orig-import)]
    (when-not (= old new)
      (spit file new))))

(def classname-pattern "[a-zA-Z_\\$][a-zA-Z\\d_\\$]*")

(defn- fix-reference-in-imports [srcdeps repl-prefix imps clj-file]
  (if-let [old-ns (->> clj-file (fs/file srcdeps) read-file-ns-decl second)]
    (map #(vector (first %) (str/replace (second %) (re-pattern (str "([^\\.])" (str/replace (name old-ns) "-" "_") "([\\. ]" classname-pattern ")")) (str "$1" (name (replacement (str/replace repl-prefix "-" "_") old-ns true)) "$2"))) imps)
    imps))

(defn- class-deps-jar!
  "creates jar containing the deps class files"
  []
  (info "jaring all class file dependencies into target/class-deps.jar")
  (with-open [file (io/output-stream "target/class-deps.jar")
              zip (ZipOutputStream. file)
              writer (io/writer zip)]
    (let [class-files (class-files)]
      (binding [*out* writer]
        (doseq [class-file class-files]
          (with-open [input (io/input-stream class-file)]
            (.putNextEntry zip (ZipEntry. (remove-2parents class-file)))
            (io/copy input zip)
            (flush)
            (.closeEntry zip)))))))

(defn- replace-class-deps! []
  (info "deleting directories with class files in target/srcdeps...")
  (doseq [class-dir (->> (java-class-dirs)
                         (map #(str/split % #"\."))
                         (map first)
                         set)]
    (fs/delete-dir (str "target/srcdeps/" class-dir))
    (info "  " class-dir " deleted"))
  (info "unzipping repackaged class-deps.jar into target/srcdeps")
  (unzip (fs/file "target/class-deps.jar") (fs/file "target/srcdeps/")))

(defn- prefix-dependency-imports! [pname pversion pprefix prefix src-path srcdeps]
  (let [cleaned-name-version (clean-name-version pname pversion)
        replace-idents (fn [text idents]
                         (reduce (fn [^String text ident]
                                   (let [pattern (re-pattern (str "([^\\.])" ident))
                                         replacement (str "$1" cleaned-name-version "." ident)]
                                     (RegExUtils/replaceAll text pattern replacement)))
                                 text
                                 idents))
        prefix (some-> (first prefix)
                       (str/replace "-" "_")
                       (str/replace "." "/"))
        clj-dep-path (relevant-clj-dep-path src-path prefix pprefix)
        clj-files (clojure-source-files-relative clj-dep-path)
        imports (->> clj-files
                     (reduce #(conj %1 (retrieve-import srcdeps (remove-2parents %2))) [])
                     (remove nil?)
                     doall)
        class-names (map class-file->fully-qualified-name (class-files))
        package-names (->> class-names
                           (map class-name->package-name)
                           set)]
    (info (format "    prefixing imports in %s clojure files in '%s' ..." (count clj-files) (first clj-dep-path)))
    (debug "class-names" class-names)
    (debug "package-names" package-names)
    (doseq [file clj-files]
      (let [orig-import (find-orig-import imports file)
            new-import (replace-idents orig-import package-names)
            old (slurp (fs/file file))
            uuid (str (UUID/randomUUID))
            new (-> old
                    (StringUtils/replace orig-import uuid)
                    (replace-idents class-names)
                    (StringUtils/replace uuid new-import))]

        (when-not (= old new)
          (debug "file: " file " orig import:" orig-import " new import:" new-import)
          (spit file new))))))

(defn- dep-frequency [dep-hierarchy]
  (let [frequency (atom {})
        freq-fn (fn [node]
                  (when-let [pkg (and (vector? node) (symbol? (first node)) (first node))]
                    (swap! frequency #(if (contains? % pkg)
                                        (update-in % [pkg] inc)
                                        (assoc % pkg 1))))
                  node)]
    (clojure.walk/postwalk freq-fn dep-hierarchy)
    @frequency))

(defn lookup-opt [opt-key opts]
  (second (drop-while #(not= % opt-key) opts)))

(defn- unzip&update-artifact!
  [{:keys [dep dep-hierarchy src-path]}
   {:keys [pname pversion pprefix uuid skip-repackage-java-classes srcdeps prefix-exclusions]}]
  (let [art-name (-> dep first name (str/split #"/") last)
        art-name-cleaned (str/replace art-name #"[\.-_]" "")
        art-version (str "v" (-> dep second (str/replace "." "v")))
        clj-files (unzip (-> dep meta :file) srcdeps)
        repl-prefix (replacement-prefix pprefix "srcdeps" src-path art-name-cleaned art-version nil)
        prefixes (apply dissoc (reduce #(assoc %1 %2 (str (replacement repl-prefix %2 nil))) {} (possible-prefixes clj-files)) prefix-exclusions)
        imports (->> clj-files
                     (reduce #(conj %1 (retrieve-import srcdeps %2)) [])
                     (remove nil?)
                     doall)
        fixed-imports (reduce (partial fix-reference-in-imports srcdeps repl-prefix) imports clj-files)]
    (info (format "  retrieving %s artifact."  art-name))
    (debug (format "    modified dependency name: %s modified version string: %s" art-name-cleaned art-version))
    (debug "   modified namespace prefix: " repl-prefix)
    (when-not skip-repackage-java-classes
      (if (.endsWith (str src-path) "target/srcdeps")
        (doall
         (map #(prefix-dependency-imports! pname pversion pprefix % (str src-path) srcdeps) prefixes))
        (prefix-dependency-imports! pname pversion pprefix nil (str src-path) srcdeps)))
    (doseq [clj-file clj-files]
      (if-let [old-ns (->> clj-file (fs/file srcdeps) read-file-ns-decl second)]
        (let [import (find-orig-import imports clj-file)
              new-ns (replacement repl-prefix old-ns nil)
              new-deftype (replacement (str/replace repl-prefix "-" "_") old-ns true)]
          (debug "    new ns:" new-ns)
          ;; garble imports
          (when-not (str/blank? import)
            (garble-import! srcdeps clj-file import uuid))
          ;; fixing generated classes/deftypes
          (when (.contains (name old-ns) "-")
            (cp/pdoseq (+ 2 (cp/ncpus)) [file (clojure-source-files [srcdeps])]
              (update-deftypes file old-ns new-deftype)))
          ;; move actual ns-s
          (move/move-ns old-ns new-ns srcdeps (file->extension (str clj-file)) [srcdeps]))
        ;; a clj file without ns
        (when-not (= "project.clj" clj-file)
          (let [old-path (str "target/srcdeps/" clj-file)
                new-path (str pprefix "/" art-name-cleaned "/" art-version "/" clj-file)]
            (fs/copy+ old-path (str "target/srcdeps/" new-path))
            ;; replace occurrences of file path references
            (doseq [file (clojure-source-files [srcdeps])]
              (update-path-in-file file clj-file new-path))
            ;; remove old file
            (fs/delete old-path)))))
    ;; fixing prefixes, degarble imports
    (cp/pdoseq (+ 2 (cp/ncpus)) [file (clojure-source-files [srcdeps])]
      (update-file file prefixes)
      (degarble-imports! fixed-imports file uuid))
    {:art-name-cleaned art-name-cleaned :art-version art-version}))

(defn- unzip&update-artifacts!
  [deps ctx]
  (loop [pending-deps (into (PersistentQueue/EMPTY) deps)]
    (when-let [{:keys [dep dep-hierarchy src-path] :as pending-dep} (peek pending-deps)]
      (let [{:keys [art-name-cleaned art-version]} (unzip&update-artifact! pending-dep ctx)
            hierarchy (dep-hierarchy dep)]
        ;; recur on transitive deps
        (recur (->> (keys hierarchy)
                    (into (pop pending-deps)
                          (keep (fn [dep]
                                  ;; omit Clojure itself
                                  (when-not (= (first dep) 'org.clojure/clojure)
                                    {:dep dep
                                     :dep-hierarchy hierarchy
                                     :src-path (fs/file src-path (str/join "/" [art-name-cleaned art-version]))}))))))))))

(defn source-deps
  "Dependencies as source: used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [{:keys [repositories dependencies source-paths root target-path name version] :as project} & args]
  (fs/copy-dir (first source-paths) (str target-path "/srcdeps"))
  (let [source-dependencies (filter source-dep? dependencies)
        opts (map #(edn/read-string %) args)
        project-prefix (lookup-opt :project-prefix opts)
        pprefix (or project-prefix (clean-name-version "mranderson" (mranderson-version)))
        srcdeps-relative (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        dep-frequencies (->> (map #(aether/resolve-dependencies :coordinates [%] :repositories repositories) source-dependencies)
                             (map #(aether/dependency-hierarchy  source-dependencies %))
                             dep-frequency)
        dep-frequency-comp (comparator #(<= (-> %1 first dep-frequencies) (-> %2 first dep-frequencies)))
        dep-hierarchy (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
                           (aether/dependency-hierarchy source-dependencies))
        ordered-hierarchy (into (sorted-map-by dep-frequency-comp) dep-hierarchy)
        uuid (str (UUID/randomUUID))
        prefix-exclusions (lookup-opt :prefix-exclusions opts)
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage opts)
        srcdeps (fs/file target-path "srcdeps")]
    (debug "skip repackage" skip-repackage-java-classes)
    (info "project prefix: " pprefix)
    (info "retrieve dependencies and munge clojure source files")
    (unzip&update-artifacts! (->> (keys ordered-hierarchy)
                                  (into [] (map (fn [dep]
                                                  {:dep dep
                                                   :dep-hierarchy dep-hierarchy
                                                   :src-path srcdeps}))))
                             {:pname name
                              :pversion version
                              :pprefix pprefix
                              :uuid uuid
                              :skip-repackage-java-classes skip-repackage-java-classes
                              :srcdeps srcdeps-relative
                              :prefix-exclusions prefix-exclusions})
    (when-not (or skip-repackage-java-classes (empty? (class-files)))
      (class-deps-jar!)
      (apply-jarjar! name version)
      (replace-class-deps!))))
