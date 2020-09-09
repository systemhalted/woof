(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.walk :as walk]
            [mount.core :as mount]
            [bzg.config :as config]
            [tea-time.core :as tt]))

;; Use a dynamic var here to use another value when testing
(def ^:dynamic db-file-name "db.edn")

;;; Core atoms and related functions

(def db
  (atom (or (try (edn/read-string (slurp db-file-name))
                 (catch Exception _ nil))
            {})))

(def db-bug-refs (atom #{}))

(defn- all-bug-refs [db]
  (into #{} (apply clojure.set/union (map :refs (vals db)))))

(add-watch
 db :serialize-bug-refs
 (fn [_ _ _ newdb]
   (reset! db-bug-refs (all-bug-refs newdb))
   (spit db-file-name (pr-str newdb))))

;;; Utility functions

(defn intern-id [m]
  (map (fn [[k v]] (assoc v :id k)) m))

(defn get-from [from]
  (:address (first from)))

(defn get-id [^String id]
  (last (re-find #"^<?(.+[^>])>?$" id)))

(defn format-link-fn
  [{:keys [from subject date id commit]} type]
  (let [shortcommit  (if (< (count commit) 8) commit (subs commit 0 8))
        mail-title   (format "Visit email sent by %s on %s" from date)
        commit-title (format "Visit commit %s made by %s" shortcommit from)]
    (condp = type
      :bug
      [:p [:a {:href   (format (:mail-url-format config/woof) id)
               :title  mail-title
               :target "_blank"}
           subject]]
      :change
      [:p
       [:a {:href   (format (:mail-url-format config/woof) id)
            :title  mail-title
            :target "_blank"}
        subject]
       " ("
       [:a {:href   (format (:commit-url-format config/woof) commit)
            :title  commit-title
            :target "_blank"}
        shortcommit] ")"]
      :release
      [:p [:a {:href   (format (:mail-url-format config/woof) id)
               :title  mail-title
               :target "_blank"}
           subject]])))

;;; Core functions to return db entries

(defn get-unfixed-bugs [db]
  (filter #(and (= (:type (val %)) "bug")
                (not (get (val %) :fixed))) db))

(defn get-unreleased-changes [db]
  (filter #(and (= (:type (val %)) "change")
                (not (get (val %) :released))) db))

(defn get-releases [db]
  (->>
   (filter #(= (:type (val %)) "release") db)
   (into {})))

(defn get-released-versions [db]
  (into #{} (map :version (vals (get-releases db)))))

;;; Core functions to update the db

(defn- update-bug-refs [id new-refs]
  (loop [refs new-refs
         ref  (some @db-bug-refs refs)]
    (when ref
      (doseq [e @db]
        (when-let [rfs (:refs (val e))]
          (when (rfs ref)
            (swap! db assoc-in [(key e) :refs] (conj rfs (get-id id)))))))
    (when-let [rest-refs (last (next (partition-by #{ref} refs)))]
      (recur rest-refs
             (some @db-bug-refs rest-refs)))))

(defn- add-change [{:keys [id from subject date-sent]} X-Woof-Change]
  (let [c-specs   (string/split X-Woof-Change #"\s")
        commit    (first c-specs)
        versions  (into #{} (next c-specs))
        released  (get-released-versions @db)
        true-from (get-from from)
        true-id   (get-id id)]
    (cond
      (and released (some released versions))
      (format "%s tried to add a change against a know release, ignoring %s"
              true-from true-id)
      (empty? versions)
      (format "%s tried to add a change with a wrong header format, ignoring %s"
              true-from true-id)
      :else
      (do (swap! db conj {true-id {:type     "change"
                                   :from     true-from
                                   :commit   commit
                                   :versions versions
                                   :subject  subject
                                   :date     date-sent}})
          (format "%s added a change for version %s via %s"
                  true-from (first versions) true-id)))))

(defn- add-confirmed-bug [{:keys [id from subject date-sent]} refs]
  (let [true-from (get-from from)
        true-id   (get-id id)]
    (swap! db conj {true-id {:type    "bug"
                             :from    true-from
                             :refs    (into #{} (conj refs true-id))
                             :subject subject
                             :date    date-sent}})
    (format "%s added a bug via %s" true-from true-id)))

(defn- add-fixed-bug [{:keys [id from date-sent]} refs]
  (let [true-from (get-from from)
        true-id   (get-id id)]
    (doseq [e (get-unfixed-bugs @db)]
      (when (some (:refs (val e)) refs)
        (swap! db assoc-in [(key e) :fixed] true-id)
        (swap! db assoc-in [(key e) :fixed-by] true-from)
        (swap! db assoc-in [(key e) :fixed-at] date-sent)))
    (format "%s marked bug fixed via %s" true-from true-id)))

(defn- add-release [{:keys [id from subject date-sent]} X-Woof-Release]
  (let [released  (get-released-versions @db)
        true-from (get-from from)
        true-id   (get-id id)]
    (cond
      ;; Prevent release when not from the release manager
      (not (= true-from (:release-manager config/woof)))
      (format "%s tried to release via %s while not being release manager"
              true-from true-id)
      ;; Prevent duplicate release
      (and released (some released #{X-Woof-Release}))
      (format "%s tried to release with a known version number via %s"
              true-from true-id)
      ;; Add the release to the db
      :else
      (do (swap! db conj {true-id {:type    "release"
                                   :from    true-from
                                   :version X-Woof-Release
                                   :subject subject
                                   :date    date-sent}})
          ;; Mark related changes as released
          (doseq [[k v] (get-unreleased-changes @db)]
            (when ((:versions v) X-Woof-Release)
              (swap! db assoc-in [k :released] X-Woof-Release)))
          (format "%s released %s via %s" true-from X-Woof-Release true-id)))))

(defn process-incoming-message
  [{:keys [id from] :as msg}]
  (let [{:keys [X-Woof-Bug X-Woof-Release X-Woof-Change
                X-Original-To X-BeenThere To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        refs
        (when (not-empty References)
          (->> (string/split References #"\s")
               (keep not-empty)
               (into #{})))]
    ;; Only process emails if they are sent from the mailing list.
    (when (some (into #{} (list X-Original-To X-BeenThere
                                (when (string? To)
                                  (last (re-find #"^.*<(.*[^>])>.*$" To)))))
                (into #{} (list (:mailing-list config/woof))))
      ;; If any email with references contains in its references the id
      ;; of a known bug, add the message-id of this mail to the refs of
      ;; this bug.
      (when refs (update-bug-refs (get-id id) refs))
      (cond
        ;; Announce a breaking change in the current development
        ;; branches and associate it with future version(s).  Anyone
        ;; can announce a breaking change.
        X-Woof-Change
        (add-change msg X-Woof-Change)
        ;; Or confirm a bug and add it to the registry.  Anyone can
        ;; confirm a bug.
        (and X-Woof-Bug (re-find #"(?i)confirmed" X-Woof-Bug))
        (add-confirmed-bug msg refs)
        ;; Or mark a bug as fixed.  Anyone can mark a bug as fixed.  If an
        ;; email contains X-Woof-Bug: fixed, we scan all refs from this
        ;; email and see if we can find a matching ref in those of a bug,
        ;; and if yes, then we mark the bug as :fixed by the message id.
        (and X-Woof-Bug refs
             (re-find #"(?i)fixed" X-Woof-Bug)
             (some @db-bug-refs refs))
        (add-fixed-bug msg refs)
        ;; Or make a release.
        X-Woof-Release
        ;; Only the release manager can announce a release.
        (add-release msg X-Woof-Release)))))

;;; Monitoring functions

(def woof-monitor (atom nil))
(defn- start-inbox-monitor! []
  (reset!
   woof-monitor
   (let [session      (mail/get-session "imaps")
         mystore      (mail/store "imaps" session
                                  (:server config/woof)
                                  (:user config/woof)
                                  (:password config/woof))
         folder       (mail/open-folder mystore (:folder config/woof) :readonly)
         idle-manager (events/new-idle-manager session)]
     (events/add-message-count-listener
      ;; Process incoming mails
      (fn [e]
        (doall
         (map #(do (println %)
                   (spit "logs.txt" (str % "\n") :append true))
              (remove nil?
                      (->> e :messages
                           (map message/read-message)
                           (map process-incoming-message2))))))
      ;; Don't process deleted mails
      nil
      folder
      idle-manager)
     idle-manager)))

(defn- start-tasks! []
  (tt/every! 1200 ;; 20 minutes
             (fn []
               (try 
                 (events/stop @woof-monitor)
                 (catch Exception _ nil))
               (start-inbox-monitor!))))

(mount/defstate woof-manager
  :start (do (start-tasks!)
             (println "Woof started"))
  :stop (when woof-manager
          (events/stop woof-monitor)
          (println "Woof stopped")))
