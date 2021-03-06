(ns bzg.woof-test
  (:require [bzg.core :as core]
            [bzg.config :as config]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer :all]
            [clojure.java.shell :as sh]))

(spec/def ::user string?)
(spec/def ::server string?)
(spec/def ::password string?)
(spec/def ::mailing-list string?)
(spec/def ::release-manager string?)
(spec/def ::mail-url-format string?)
(spec/def ::commit-url-format string?)
(spec/def ::folder string?)
(spec/def ::project-name string?)
(spec/def ::project-url string?)
(spec/def ::title string?)
(spec/def ::base-url string?)
(spec/def ::feed-title string?)
(spec/def ::feed-description string?)
(spec/def ::port string?)

(spec/def ::config
  (spec/keys :req-un [::user
                      ::server
                      ::password
                      ::mailing-list
                      ::mail-url-format
                      ::commit-url-format
                      ::release-manager
                      ::folder
                      ::project-url
                      ::project-name
                      ::title
                      ::base-url
                      ::feed-title
                      ::feed-description
                      ::port]))

(deftest configuration
  (testing "Testing configuration"
    (is (spec/valid? ::config config/woof))))

(def test-data
  {:msg1  {:id        "id1"
           :subject   "[BUG] Confirmed bug"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Bug" "confirmed"}]}
   :msg2  {:id        "id2"
           :subject   "[FIXED] Fixed bug"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id1"}
                       {"X-Woof-Bug" "fixed"}]}
   :msg3  {:id        "id3"
           :subject   "Incompatible change for 8.3"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Change" "commithash 8.3"}]}
   :msg4  {:id        "id4"
           :subject   "Release 8.3"
           :from      (list {:address (:release-manager config/woof)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Release" "8.3"}]}
   :msg5  {:id        "id5"
           :subject   "Release 8.4"
           :from      (list {:address (:release-manager config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Release" "8.4"}]}
   :msg6  {:id        "id6"
           :subject   "[BUG] Bug in release 8.3"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id4 id0"}
                       {"X-Woof-Bug" "confirmed"}]}
   :msg7  {:id        "id7"
           :subject   "Fix for bug wrt release 8.3"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id4"}
                       {"X-Woof-Bug" "fixed"}]}
   :msg8  {:id        "id8"
           :subject   "A call for help"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id7"}
                       {"X-Woof-Help" "true"}]}
   :msg9  {:id        "id9"
           :subject   "Resolved (was: A call for help)"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:14:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id8"}
                       {"X-Woof-Help" "done"}]}
   :msg10 {:id        "id10"
           :subject   "Incompatible change for 8.4"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-06-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Change" "8.4"}]}
   :msg11 {:id        "id11"
           :subject   "A call for help, with an annotation from the header"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id7"}
                       {"X-Woof-Help" "This is a call for help."}]}
   :msg12 {:id        "id12"
           :subject   "[BUG] Confirmed bug"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Bug" "This is the annotation for this bug."}]}
   :msg13 {:id        "id13"
           :subject   "[PATCH] A patch with a header"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"X-Woof-Patch" "true"}]}
   :msg14 {:id        "id14"
           :subject   "Re: [PATCH] A patch with a header"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id13"}
                       {"X-Woof-Patch" "applied"}]}
   :msg15 {:id        "id15"
           :subject   "Re: [PATCH] A patch with a header"
           :from      (list {:address (:user config/woof)})
           :date-sent #inst "2020-05-28T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/woof)}
                       {"References" "id13"}]
           :body      {:body "Applied"}}})

(deftest message-processing
  (binding [core/db-file-name "db-test.edn"]
    (testing "Add a bug"
      (core/process-incoming-message (:msg1 test-data))
      (is (= 1 (count @core/db)))
      (is (not-empty (get @core/db "id1")))
      (reset! core/db {}))
    (testing "Add a bug, annotating from the header"
      (core/process-incoming-message (:msg12 test-data))
      (is (= 1 (count @core/db)))
      (is (not-empty (get @core/db "id12")))
      (reset! core/db {}))
    (testing "Add a bug and fix it"
      (core/process-incoming-message (:msg1 test-data))
      (core/process-incoming-message (:msg2 test-data))
      (is (= 1 (count @core/db)))
      (is (= 0 (count (core/get-unfixed-bugs @core/db))))
      (reset! core/db {}))
    (testing "Add a release"
      (core/process-incoming-message (:msg4 test-data))
      ;; Ignore a second release with the same version
      (core/process-incoming-message (:msg4 test-data))
      (is (= 1 (count (core/get-releases @core/db))))
      (reset! core/db {}))
    (testing "Add a change with a commit"
      (core/process-incoming-message (:msg3 test-data))
      (is (= 1 (count (core/get-unreleased-changes @core/db))))
      (reset! core/db {}))
    (testing "Add a change without a commit"
      (core/process-incoming-message (:msg10 test-data))
      (is (= 1 (count (core/get-unreleased-changes @core/db))))
      (reset! core/db {}))
    (testing "Add a release wrt to a change"
      (core/process-incoming-message (:msg3 test-data))
      (is (= 1 (count (core/get-unreleased-changes @core/db))))
      (core/process-incoming-message (:msg4 test-data))
      (is (= 0 (count (core/get-unreleased-changes @core/db))))
      (reset! core/db {}))
    (testing "Fix a bug a release wrt to a change"
      (core/process-incoming-message (:msg4 test-data))
      (core/process-incoming-message (:msg5 test-data))
      (core/process-incoming-message (:msg6 test-data))
      (is (= 1 (count (core/get-unfixed-bugs @core/db))))
      (core/process-incoming-message (:msg7 test-data))
      (is (= 0 (count (core/get-unfixed-bugs @core/db))))
      (reset! core/db {}))
    (testing "Add a call for help"
      (core/process-incoming-message (:msg8 test-data))
      (is (= 1 (count (core/get-pending-help @core/db))))
      (reset! core/db {}))
    (testing "Add a call for help, annotating from the header"
      (core/process-incoming-message (:msg11 test-data))
      (is (= 1 (count (core/get-pending-help @core/db))))
      (reset! core/db {}))
    (testing "Cancel a call for help"
      (core/process-incoming-message (:msg8 test-data))
      (is (= 1 (count (core/get-pending-help @core/db))))
      (core/process-incoming-message (:msg9 test-data))
      (is (= 0 (count (core/get-pending-help @core/db))))
      (reset! core/db {}))
    (testing "Add a patch and fix it with a header"
      (core/process-incoming-message (:msg13 test-data))
      (is (= 1 (count (core/get-unapplied-patches @core/db))))
      (core/process-incoming-message (:msg14 test-data))
      (is (= 0 (count (core/get-unapplied-patches @core/db))))
      (reset! core/db {}))
    (testing "Add a patch and fix it with \"Applied\""
      (core/process-incoming-message (:msg13 test-data))
      (is (= 1 (count (core/get-unapplied-patches @core/db))))
      (core/process-incoming-message (:msg15 test-data))
      (is (= 0 (count (core/get-unapplied-patches @core/db))))
      (reset! core/db {}))
    (sh/sh "rm" "db-test.edn")))
