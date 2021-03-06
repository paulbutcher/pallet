(ns pallet.core-test
  (:use pallet.core)
  (:require
   [pallet.action :as action]
   [pallet.argument :as argument]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.core :as core]
   [pallet.mock :as mock]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.string :as string]
   [clojure.stacktrace :as stacktrace]
   [clojure.tools.logging :as logging])
  (:use
   clojure.test
   [pallet.actions :only [exec-script]]
   [pallet.executors :only [force-target-via-ssh-executor]]
   [pallet.monad :only [phase-pipeline session-pipeline
                        as-session-pipeline-fn session-peek-fn]]
   [pallet.monad.state-accessors :only [assoc-in-state]]
   [pallet.test-utils :only [script-action clj-action test-session]]))

(use-fixtures :once (logutils/logging-threshold-fixture))

;; tests run with node-list, as no external dependencies

;; Allow running against other compute services if required
(deftest with-admin-user-test
  (let [x (rand)]
    (with-admin-user [x]
      (is (= x (:username pallet.utils/*admin-user*))))))

;; this test doesn't work too well if the test are run in more than
;; one thread...
#_
(deftest admin-user-test
  (let [username "userfred"
        old pallet.utils/*admin-user*]
    (admin-user username)
    (is (map? pallet.utils/*admin-user*))
    (is (= username (:username pallet.utils/*admin-user*)))
    (is (= (pallet.utils/default-public-key-path)
           (:public-key-path pallet.utils/*admin-user*)))
    (is (= (pallet.utils/default-private-key-path)
           (:private-key-path pallet.utils/*admin-user*)))
    (is (nil? (:password pallet.utils/*admin-user*)))

    (admin-user username :password "pw" :public-key-path "pub"
                :private-key-path "pri")
    (is (map? pallet.utils/*admin-user*))
    (is (= username (:username pallet.utils/*admin-user*)))
    (is (= "pub" (:public-key-path pallet.utils/*admin-user*)))
    (is (= "pri" (:private-key-path pallet.utils/*admin-user*)))
    (is (= "pw" (:password pallet.utils/*admin-user*)))

    (admin-user old)
    (is (= old pallet.utils/*admin-user*))))

(def ubuntu-node (node-spec :image {:os-family :ubuntu}))

(deftest group-with-prefix-test
  (is (= {:group-name :pa}
         (#'core/group-with-prefix "p" (test-utils/group :a)))))

(deftest node-map-with-prefix-test
  (is (= {{:group-name :pa} 1}
         (#'core/node-map-with-prefix "p" {(test-utils/group :a) 1}))))

(deftest group-spec?-test
  (is (#'core/group-spec? (core/group-spec "a")))
  (is (#'core/group-spec? (core/group-spec "a" :extends (server-spec)))))

(deftest nodes-in-set-test
  (let [a (group-spec :a :node-spec ubuntu-node)
        b (group-spec :b :node-spec ubuntu-node)
        a-node (test-utils/make-node "a")
        b-node (test-utils/make-node "b")]
    (testing "sequence of groups"
      (let []
        (is (= {a #{a-node} b #{b-node}}
               (#'core/nodes-in-set [a b] nil [a-node b-node])))))
    (testing "explicit nodes"
      (is (= {a #{a-node}}
             (#'core/nodes-in-set {a a-node} nil nil)))
      (is (= {a #{a-node b-node}}
             (#'core/nodes-in-set {a #{a-node b-node}} nil nil)))
      (is (= {a #{a-node} b #{b-node}}
             (#'core/nodes-in-set {a #{a-node} b #{b-node}} nil nil))))
    (let [pa (group-spec :pa :node-spec ubuntu-node)
          pb (group-spec :pb :node-spec ubuntu-node)]
      (is (= {pa #{a-node}}
             (#'core/nodes-in-set {a a-node} "p" nil)))
      (is (= {pa #{a-node b-node}}
             (#'core/nodes-in-set {a #{a-node b-node}} "p" nil)))
      (is (= {pa #{a-node} pb #{b-node}}
             (#'core/nodes-in-set {a #{a-node} b #{b-node}} "p" nil)))
      (is (= {pa #{a-node} pb #{b-node}}
             (#'core/nodes-in-set {a a-node b b-node} "p" nil))))))

(deftest node-in-types?-test
  (let [a (group-spec :a)
        b (group-spec :b)]
    (is (#'core/node-in-types? [a b] (test-utils/make-node "a")))
    (is (not (#'core/node-in-types? [a b] (test-utils/make-node "c"))))))

(deftest nodes-for-group-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")]
    (is (= [nb] (#'core/nodes-for-group [na nb nc] b)))
    (is (= [na] (#'core/nodes-for-group [na nc] a)))))

(deftest group-spec-with-count-test
  (let [a (group-spec "a")
        b (group-spec "b")]
    (is (= [(assoc a :count 1) (assoc b :count 2)]
             (map #'core/group-spec-with-count {a 1 b 2})))))


(deftest server-test
  (let [a (group-spec :a)
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= {:node-id :id
            :group-name :a
            :packager :aptitude
            :image {:os-version "v"
                    :os-family :ubuntu}
            :node n}
           (server a n {})))))

(deftest groups-with-servers-test
  (let [a (group-spec :a)
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= [{:servers [{:node-id :id
                        :group-name :a
                        :packager :aptitude
                        :image {:os-version "v"
                                :os-family :ubuntu}
                        :node n
                        :invoke-only false}]
             :group-name :a}]
             (groups-with-servers {a #{n}} #{n})))
    (testing "with invoke-only"
      (is (= [{:servers [{:node-id :id
                          :group-name :a
                          :packager :aptitude
                          :image {:os-version "v"
                                  :os-family :ubuntu}
                          :node n
                          :invoke-only true}]
               :group-name :a}]
             (groups-with-servers {a #{n}} (constantly false)))))))

(deftest session-with-groups-test
  (let [a (group-spec :a)
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= [nil {:groups [{:servers [{:node-id :id
                                      :group-name :a
                                      :packager :aptitude
                                      :image {:os-version "v"
                                              :os-family :ubuntu}
                                      :node n
                                      :invoke-only false}]
                           :group-name :a}]
                 :all-nodes [n]
                 :selected-nodes [n]
                 :node-set {a #{n}}}]
           (session-with-groups
             {:all-nodes [n] :selected-nodes [n] :node-set {a #{n}}})))
    (testing "with-options"
      (is (= [nil {:groups [{:servers [{:node-id :id
                                        :group-name :a
                                        :packager :aptitude
                                        :image {:os-version "v"
                                                :os-family :ubuntu}
                                        :node n
                                        :invoke-only true}]
                             :group-name :a}]
                   :all-nodes [n]
                   :selected-nodes [n]
                   :node-set nil
                   :all-node-set [{a #{n}}]}]
             (session-with-groups
               {:all-nodes [n] :selected-nodes [n]
                :node-set nil :all-node-set [{a #{n}}]}))))))

(deftest session-with-environment-test
  (binding [pallet.core/*middleware* :middleware]
    (testing "defaults"
      (is (= [nil {:blobstore nil :compute nil :user utils/*admin-user*
                   :middleware :middleware
                   :algorithms core/default-algorithms
                   :executor core/default-executor}
              (:environment (#'core/session-with-environment {}))])))
    (testing "passing a prefix"
      (let [v (#'core/session-with-environment {:prefix "prefix"})]
        (is (= "prefix" (:prefix v)))
        (is (= {:blobstore nil :compute nil :user utils/*admin-user*
                :middleware *middleware*
                :algorithms core/default-algorithms
                :executor core/default-executor}
               (:environment v)))))
    (testing "passing a user"
      (let [user (utils/make-user "fred")
            v (#'core/session-with-environment {:user user})]
        (is (= {:blobstore nil :compute nil  :user user
                :middleware :middleware
                :algorithms core/default-algorithms
                :executor core/default-executor}
               (:environment v)))))))

(deftest node-spec-test
  (is (= {:image {}}
         (node-spec :image {})))
  (is (= {:hardware {}}
         (node-spec :hardware {}))))

(deftest server-spec-test
  (is (= {:phases {:a 1}}
         (server-spec :phases {:a 1})))
  (is (= {:phases {:a 1} :image {:b 2}}
         (server-spec :phases {:a 1} :node-spec (node-spec :image {:b 2})))
      "node-spec merged in")
  (is (= {:phases {:a 1} :image {:b 2} :hardware {:hardware-id :id}}
         (server-spec
          :phases {:a 1}
          :node-spec (node-spec :image {:b 2})
          :hardware {:hardware-id :id}))
      "node-spec keys moved to :node-spec keyword")
  (is (= {:phases {:a 1} :image {:b 2}}
         (server-spec
          :extends (server-spec :phases {:a 1} :node-spec {:image {:b 2}})))
      "extends a server-spec")
  (is (= {:roles #{:r1}} (server-spec :roles :r1)) "Allow roles as keyword")
  (is (= {:roles #{:r1}} (server-spec :roles [:r1])) "Allow roles as sequence"))

(deftest group-spec-test
  (is (= {:group-name :gn :phases {:a 1}}
         (group-spec "gn" :extends (server-spec :phases {:a 1}))))
  (is (= {:group-name :gn :phases {:a 1} :image {:b 2}}
         (group-spec
          "gn"
          :extends [(server-spec :phases {:a 1})
                    (server-spec :node-spec {:image {:b 2}})])))
  (is (= {:group-name :gn :phases {:a 1} :image {:b 2} :roles #{:r1 :r2 :r3}}
         (group-spec
          "gn"
          :roles :r1
          :extends [(server-spec :phases {:a 1} :roles :r2)
                    (server-spec :node-spec {:image {:b 2}} :roles [:r3])]))))

(deftest cluster-spec-test
  (let [x (fn [x] (update-in x [:x] inc))
        gn (group-spec "gn" :count 1 :phases {:x (fn [x] (assoc x :x 1))})
        go (group-spec "go" :count 2 :phases {:o (fn [x] (assoc x :o 1))})
        cluster (cluster-spec
                 "cl"
                 :phases {:x x}
                 :groups [gn go]
                 :node-spec {:image {:os-family :ubuntu}})]
    (is (= 2 (count (:groups cluster))))
    (testing "names are prefixed"
      (is (= :cl-gn (:group-name (first (:groups cluster)))))
      (is (= :cl-go (:group-name (second (:groups cluster))))))
    (testing ":phases on nodes are propogated"
      (is (= {:o 1} ((-> cluster :groups second :phases :o) {}))))
    (testing ":phases on cluster are merged"
      (is (= {:x 2} ((-> cluster :groups first :phases :x) {}))))))

(deftest make-node-test
  (is (= {:group-name :fred :image {:os-family :ubuntu}}
         (group-spec "fred" :node-spec {:image {:os-family :ubuntu}})))
  (is (= {:group-name :tom :image {:os-family :centos}}
         (group-spec "tom" :node-spec {:image {:os-family :centos}}))))

(deftest defnode-test
  (logging/info "defnode-test start")
  (defnode fred {:os-family :ubuntu})
  (is (= {:group-name :fred :image {:os-family :ubuntu}} fred))
  (defnode tom "This is tom" {:os-family :centos})
  (is (= {:group-name :tom :image {:os-family :centos}} tom))
  (is (= "This is tom" (:doc (meta #'tom))))
  (defnode harry (tom :image))
  (is (= {:group-name :harry :image {:os-family :centos}} harry))
  (let [test-component (clj-action [session arg] [(str arg) session])
        node-with-phases (make-node
                          "node-with-phases" (tom :image)
                          :bootstrap (test-component :a)
                          :configure  (test-component :b))]
    (is (= #{:bootstrap :configure} (set (keys (node-with-phases :phases)))))
    (let [session (test-utils/test-session
                   {:server {:node-id :id
                             :group-name :group-name
                             :packager :yum
                             :image {}
                             :node (test-utils/make-node "group-name" :id "id")
                             :phases (:phases node-with-phases)}
                    :target-id :id :target-type :server})]
      (is (= ":a"
             (first
              (build-actions/produce-phases
               (assoc session :phase :bootstrap)))))
      (is (= ":b"
             (first
              (build-actions/produce-phases
               (assoc session :phase :configure)))))))
  (logging/info "defnode-test end"))

(def identity-action
  (script-action [session x] [[{:language :bash} x] session]))
(def identity-local-action (clj-action [session] [session session]))

(deftest bootstrap-script-test
  (is (= "a"
         (#'core/bootstrap-script
          (test-session
           {:group {:image {:os-family :ubuntu}
                    :packager :aptitude
                    :phases {:bootstrap (identity-action "a")}}}))))
  (testing "rejects local actions"
    (is (re-find
         #":error.*local actions"
         (#'core/bootstrap-script
          (test-session
           {:group
            {:image {:os-family :ubuntu}
             :packager :aptitude
             :phases {:bootstrap (identity-local-action)}}
            :environment {:algorithms core/default-algorithms}})))))
  (testing "requires a packager"
    (is (thrown?
         java.lang.AssertionError
         (#'core/bootstrap-script
          (test-session {:group {:image {:os-family :ubuntu}}})))))
  (testing "requires an os-family"
    (is (thrown?
         java.lang.AssertionError
         (#'core/bootstrap-script
          (test-session {:group {:packager :yum}}))))))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(clj-action
       [session]
       (logging/infof "Seenfn %s" name)
       (testing (format "not already seen %s" name)
         (is (not @seen)))
       (reset! seen true)
       (is (:server session))
       (is (:group session))
       [@seen session])
     seen?]))

(deftest warn-on-undefined-phase-test
  (testing "return value"
    (is (= [nil {:a 1}] (#'core/warn-on-undefined-phase {:a 1}))))
  (testing "no defined phases"
    (is (= "warn Undefined phases: a, b\n"
           (with-out-str
             (logutils/logging-to-stdout
              (#'core/warn-on-undefined-phase
               {:groups nil :phase-list [:a :b]}))))))
  (testing "some undefined phases"
    (is (= "warn Undefined phases: b\n"
           (with-out-str
             (logutils/logging-to-stdout
              (#'core/warn-on-undefined-phase
               {:groups [{:phases {:a identity}}]
                :phase-list [:a :b]})))))))

(deftest identify-anonymous-phases-test
  (testing "with keyword"
    (is (= [nil {:phase-list [:a]}]
           (#'core/identify-anonymous-phases {:phase-list [:a]}))))
  (testing "with non-keyword"
    (let [[_ session] (#'core/identify-anonymous-phases {:phase-list ['a]})]
      (is (every? keyword (:phase-list session)))
      (is (= 'a
             (get (:inline-phases session) (first (:phase-list session))))))))

(deftest session-with-default-phase-test
  (testing "with empty phase list"
    (is (= [nil {:phase-list [:settings :configure]}]
           (#'core/session-with-default-phase {}))))
  (testing "with non-empty phase list"
    (is (= [nil {:phase-list [:settings :a]}]
           (#'core/session-with-default-phase {:phase-list [:a]})))))

(deftest session-with-configure-phase-test
  (testing "with empty phase-list"
    (is (= [nil {:phase-list [:settings :configure]}]
           (#'core/session-with-configure-phase {}))))
  (testing "with phase list without configure"
    (is (= [nil {:phase-list [:settings :configure :a]}]
           (#'core/session-with-configure-phase {:phase-list [:a]}))))
  (testing "with phase list with configure"
    (is (= [nil {:phase-list [:settings :a :configure]}]
           (#'core/session-with-configure-phase
             {:phase-list [:a :configure]})))))

(deftest lift-test
  (logging/info "lift-test begin")
  (testing "node-list"
    (let [local (group-spec "local")
          [localf seen?] (seen-fn "lift-test")
          localhost (node-list/make-localhost-node :group-name "local")
          service (compute/compute-service "node-list" :node-list [localhost])]
      (let [session (lift
                     local
                     :phase [(phase/phase-fn
                              (exec-script (~lib/ls "/"))) (localf)]
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :compute service)]
        (is (re-find #"bin" (-> session :results :localhost pr-str)))
        (is (seen?)))
      (testing "invalid :phases keyword"
        (is (thrown-with-msg?
              slingshot.ExceptionInfo #":phases"
              (lift local :phases []))))
      (testing "invalid keyword"
        (is (thrown-with-msg?
              slingshot.ExceptionInfo #"Invalid"
              (lift local :abcdef []))))))
  (logutils/suppress-logging
   (testing "throw on remote bash error"
     (let [local (group-spec
                  "local"
                  :phases {:configure (phase/phase-fn
                                       (exec-script (~lib/exit 1)))})
           localhost (node-list/make-localhost-node :group-name "local")
           service (compute/compute-service "node-list" :node-list [localhost])
           thrown (atom false)]
       (try
         (lift
          local
          :user (assoc utils/*admin-user*
                  :username (test-utils/test-username)
                  :no-sudo true)
          :compute service
          :environment {:executor force-target-via-ssh-executor})
         (is false "should throw")
         (catch Exception e
           (let [e (stacktrace/root-cause e)]
             (is (instance? slingshot.ExceptionInfo e))
             (is (re-find #"Error executing script" (.getMessage e)))
             (reset! thrown true))))
       (is @thrown)))
   (testing "throw on remote bash error after other actions"
     (let [clj-fn (clj-action [session] [session session])
           local (group-spec
                  "local"
                  :phases {:configure (phase/phase-fn
                                       (exec-script (~lib/exit 1))
                                       (clj-fn)
                                       (exec-script (~lib/exit 1)))})
           localhost (node-list/make-localhost-node :group-name "local")
           service (compute/compute-service "node-list" :node-list [localhost])
           thrown (atom false)]
       (try
         (lift
          local
          :user (assoc utils/*admin-user*
                  :username (test-utils/test-username)
                  :no-sudo true)
          :compute service
          :environment {:executor force-target-via-ssh-executor})
         (is false "should throw")
         (catch Exception e
           (let [e (stacktrace/root-cause e)]
             (is (instance? slingshot.ExceptionInfo e))
             (is (re-find #"Error executing script" (.getMessage e)))
             (reset! thrown true))))
       (is @thrown))))
  (logging/info "lift-test end"))

(deftest lift-parallel-test
  (let [local (group-spec "local")]
    (testing "node-list"
      (let [[localf seen?] (seen-fn "lift-parallel-test")
            service (compute/compute-service
                     "node-list"
                     :node-list [(node-list/make-localhost-node :tag "local")])]
        (is (re-find
             #"bin"
             (->
              (lift local
                    :phase [(phase/phase-fn (exec-script (~lib/ls "/")))
                            (phase/phase-fn (localf))]
                    :user (assoc utils/*admin-user*
                            :username (test-utils/test-username)
                            :no-sudo true)
                    :compute service
                    :environment
                    {:algorithms {:lift-fn #'pallet.core/parallel-lift}})
              :results :localhost pr-str)))
        (is (seen?))
        (testing "invalid :phases keyword"
          (is (thrown-with-msg?
                slingshot.ExceptionInfo
                #":phases"
                (lift local :phases []))))
        (testing "invalid keyword"
          (is (thrown-with-msg?
                slingshot.ExceptionInfo
                #"Invalid"
                (lift local :abcdef []))))))))

(deftest lift2-test
  (let [[localf seen?] (seen-fn "x")
        [localfy seeny?] (seen-fn "y")
        compute (compute/compute-service
                 "node-list"
                 :node-list [(node-list/make-localhost-node
                              :group-name "x1" :name "x1" :id "x1"
                              :os-family :ubuntu)
                             (node-list/make-localhost-node
                              :group-name "y1" :name "y1" :id "y1"
                              :os-family :ubuntu)])
        x1 (group-spec :x1 :phases {:configure (localf)})
        y1 (group-spec :y1 :phases {:configure (localfy)})]
    (is (map?
         (lift [x1 y1]
               :user (assoc utils/*admin-user*
                       :username (test-utils/test-username)
                       :no-sudo true)
               :compute compute)))
    (is (seen?) "seen-fn x called")
    (is (seeny?) "seen-fn y called")))

(deftest lift2-parallel-test
  (let [[localf seen?] (seen-fn "lift-parallel test x")
        [localfy seeny?] (seen-fn "lift-parallel test y")
        compute (compute/compute-service
                 "node-list"
                 :node-list [(node-list/make-localhost-node
                              :group-name "x1" :name "x1" :id "x1"
                              :os-family :ubuntu)
                             (node-list/make-localhost-node
                              :group-name "y1" :name "y1" :id "y1"
                              :os-family :ubuntu)])
        x1 (group-spec :x1 :phases {:configure (localf)})
        y1 (group-spec :y1 :phases {:configure (localfy)})]
    (is (map?
         (lift [x1 y1]
               :user (assoc utils/*admin-user*
                       :username (test-utils/test-username)
                       :no-sudo true)
               :compute compute
               :environment
               {:algorithms {:lift-fn #'pallet.core/parallel-lift}})))
    (is (seen?))
    (is (seeny?))))

(deftest lift*-nodes-binding-test
  (logging/info "lift*-nodes-binding-test start")
  (let [a (group-spec "a")
        b (group-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c" :running false)]

    (testing "single group"
        (mock/expects [(sequential-apply-phase
                        [session]
                        (do
                          (is (= #{na nb} (set (:selected-nodes session))))
                          (is (= #{na nb} (set (:all-nodes session))))
                          (is (= #{na nb} (set
                                           (map
                                            :node
                                            (-> session :group :servers)))))
                          (is (= #{na nb}
                                 (set (map
                                       :node
                                       (-> session :groups first :servers)))))
                          [[] session]))]
                      (lift*
                       (test-session
                        {:node-set {a #{na nb nc}}
                         :phase-list [:configure]
                         :executor core/default-executor
                         :environment
                         {:compute nil
                          :user utils/*admin-user*
                          :middleware *middleware*
                          :algorithms {:lift-fn sequential-lift}}}))))
    (testing "single multi-group"
      (mock/expects [(sequential-apply-phase
                      [session]
                      (do
                        (is (= #{na nb} (set (:selected-nodes session))))
                        (is (= na
                               (-> session
                                   :groups first :servers first :node)))
                        (is (= nb
                               (-> session
                                   :groups second :servers first :node)))
                        [[] session]))]
                    (lift*
                     (test-session
                      {:node-set {a #{na} b #{nb}}
                       :phase-list [:configure]
                       :executor core/default-executor
                       :environment
                       {:compute nil
                        :user utils/*admin-user*
                        :middleware *middleware*
                        :algorithms {:lift-fn sequential-lift}}})))))
    (logging/info "lift*-nodes-binding-test end"))

(deftest lift-multiple-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")
        compute (compute/compute-service "node-list" :node-list [na nb nc])]
    (mock/expects [(compute/nodes [_] [na nb nc])
                   (sequential-apply-phase
                    [session]
                    (do
                      (is (= #{na nb nc} (set (:all-nodes session))))
                      (let [m (into
                               {}
                               (map
                                (juxt :group-name identity) (:groups session)))]
                        (is (= na (-> m :a :servers first :node)))
                        (is (= nb (-> m :b :servers first :node)))
                        (is (= 2 (count (:groups session)))))
                      (is (= 1 (-> session :parameters :x)))
                      []))]
                  (lift [a b] :compute compute :parameters {:x 1}))))

(deftest lift-with-runtime-params-test
  ;; test that parameters set at execution time are propagated
  ;; between phases
  (let [assoc-runtime-param (clj-action [session]
                              [session
                               (parameter/assoc-for-target session [:x] "x")])

        get-runtime-param (script-action [session]
                            [[{:language :bash}
                              (format
                               "echo %s"
                               (parameter/get-for-target session [:x]))]
                             session])
        node (group-spec
              "localhost"
              :phases
              {:configure (assoc-runtime-param)
               :configure2 (fn [session]
                             (is (= (parameter/get-for-target session [:x])
                                    "x"))
                             ((get-runtime-param) session))})
        localhost (node-list/make-localhost-node :group-name "localhost")]
    (testing "serial"
      (let [compute (compute/compute-service "node-list" :node-list [localhost])
            session (lift {node localhost}
                          :phase [:configure :configure2]
                          :user (assoc utils/*admin-user*
                                  :username (test-utils/test-username)
                                  :no-sudo true)
                          :compute compute
                          :environment
                          {:algorithms {:lift-fn #'core/sequential-lift}})]
        (is (map? session))
        (is (map? (-> session :results)))
        (is (map? (-> session :results first second)))
        (is (-> session :results :localhost :configure))
        (is (-> session :results :localhost :configure2))
        (let [{:keys [out err exit]} (-> session
                                         :results :localhost :configure2 first)]
          (is out)
          (is (string/blank? err))
          (is (zero? exit)))))
    (testing "parallel"
      (let [compute (compute/compute-service "node-list" :node-list [localhost])
            session (lift {node localhost}
                          :phase [:configure :configure2]
                          :user (assoc utils/*admin-user*
                                  :username (test-utils/test-username)
                                  :no-sudo true)
                          :compute compute
                          :environment
                          {:algorithms {:lift-fn #'core/parallel-lift}})]
        (is (map? session))
        (is (map? (-> session :results)))
        (is (map? (-> session :results first second)))
        (is (-> session :results :localhost :configure))
        (is (-> session :results :localhost :configure2))
        (let [{:keys [out err exit]} (-> session
                                         :results :localhost :configure2 first)]
          (is out)
          (is (string/blank? err))
          (is (zero? exit)))))))

(def dummy-local-resource
  (clj-action [session arg] [session session]))

(deftest lift-with-delayed-argument-test
  ;; test that delayed arguments correcly see parameter updates
  ;; within the same phase
  (let [add-slave (phase-pipeline add-slave {}
                    [target-node session/target-node]
                    (parameter/update-service
                     [:slaves]
                     (fn [v]
                       (conj (or v #{})
                             (str (compute/hostname target-node) "-"
                                  (compute/primary-ip target-node))))))
        seen (atom false)
        get-slaves (fn [session]
                     (reset! seen true)
                     (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                            (parameter/get-for-service session [:slaves]))))
        get-slave (phase-pipeline get-slave {}
                    (dummy-local-resource
                     (argument/delayed [session]
                      (get-slaves session))))
        master (group-spec "master" :phases {:configure get-slave})
        slave (group-spec "slave" :phases {:configure add-slave})
        slaves [(test-utils/make-localhost-node
                 :name "a" :id "a" :group-name "slave")
                (test-utils/make-localhost-node
                 :name "b" :id "b" :group-name "slave")]
        master-node (test-utils/make-localhost-node
                     :name "c" :group-name "master")
        compute (compute/compute-service
                 "node-list" :node-list (conj slaves master-node))]
    (testing "serial"
      (let [session (lift
                     [master slave]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn sequential-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
               (parameter/get-for-service session [:slaves]))))
      (testing "node sequence neutrality"
        (reset! seen false)
        (let [session (lift
                       [slave master]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is @seen "get-slaves should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service session [:slaves]))))))
    (testing "parallel"
      (reset! seen false)
      (let [session (lift
                     [master slave]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms
                                   {:lift-fn #'core/parallel-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
               (parameter/get-for-service session [:slaves])))))))

(def checking-set
  (clj-action [session]
    (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
           (parameter/get-for-service session [:slaves])))
    [session session]))

(deftest lift-post-phase-test
  (testing
      "test that parameter updates are correctly seen in the post phase"
    (let [add-slave (phase-pipeline add-slave {}
                      [target-node session/target-node]
                      (parameter/update-service
                       [:slaves]
                       (fn [v]
                         (conj (or v #{})
                               (str (compute/hostname target-node) "-"
                                    (compute/primary-ip target-node))))))
          slave (group-spec "slave" :phases {:configure add-slave})
          slaves [(test-utils/make-localhost-node
                   :name "a" :id "a" :group-name "slave")
                  (test-utils/make-localhost-node
                   :name "b" :id "b" :group-name "slave")]
          master-node (test-utils/make-localhost-node
                       :name "c" :id "c" :group-name "master")
          compute (compute/compute-service
                   "node-list" :node-list (conj slaves master-node))]
      (testing "with serial lift"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (group-spec
                      "master"
                      :phases {:configure (phase/phase-fn
                                           (phase/schedule-in-pre-phase
                                            (checking-set)
                                            (localf-pre))
                                           (phase/schedule-in-post-phase
                                            (checking-set)
                                            (localf-post)))})

              session (lift
                       [master slave]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service session [:slaves])))))
      (testing "with serial lift in reverse node type order"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (group-spec
                      "master"
                      :phases {:configure (phase/phase-fn
                                           (phase/schedule-in-pre-phase
                                            (checking-set)
                                            (localf-pre))
                                           (phase/schedule-in-post-phase
                                            (checking-set)
                                            (localf-post)))})

              session (lift
                       [slave master]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service session [:slaves])))))
      (testing "with parallel lift"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (group-spec
                      "master"
                      :phases {:configure (phase/phase-fn
                                           (phase/schedule-in-pre-phase
                                            (checking-set)
                                            (localf-pre))
                                           (phase/schedule-in-post-phase
                                            (checking-set)
                                            (localf-post)))})

              session (lift
                       [master slave]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms
                                     {:lift-fn #'core/parallel-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service session [:slaves]))))))))

(deftest expand-cluster-groups-test
  (let [g1 (core/group-spec :g1 :phases nil :environment nil)
        g2 (core/group-spec :g2 :phases nil :environment nil)
        c1 (core/cluster-spec :c1 :groups [g1])
        c2 (core/cluster-spec :c2 :groups [c1 g2])]
    (is (= [g1] (core/expand-cluster-groups g1)))
    (is (= [{g1 :opaque}] (core/expand-cluster-groups {g1 :opaque})))
    (is (= [g1] (core/expand-cluster-groups [g1])))
    (is (= [g1 g2] (core/expand-cluster-groups [g1 g2])))
    (is (= [(assoc g1 :group-name :c1-g1 :count 1)]
             (core/expand-cluster-groups [c1])))
    (is (= [(assoc g1 :group-name :c2-c1-g1 :count 1)
            (assoc g2 :group-name :c2-g2 :count 1)]
             (core/expand-cluster-groups c2)))))

(deftest expand-group-spec-with-counts-test
  (let [g1 (core/group-spec :g1 :phases nil :environment nil :count 2)
        g2 (core/group-spec :g2 :phases nil :environment nil :count 3)
        c1 (core/cluster-spec :c1 :groups [g1])
        c2 (core/cluster-spec :c2 :groups [(assoc c1 :count 2) g2])]
    (is (= [g1] (core/expand-group-spec-with-counts g1)))
    (is (= [(assoc g1 :count 1)] (core/expand-group-spec-with-counts {g1 1})))
    (is (= [g1] (core/expand-group-spec-with-counts [g1])))
    (is (= [g1 g2] (core/expand-group-spec-with-counts [g1 g2])))
    (is (= [(assoc g1 :count 1) (assoc g2 :count 2)]
             (core/expand-group-spec-with-counts [{g1 1} {g2 2}])))
    (is (= [(assoc g1 :group-name :c1-g1)]
             (core/expand-group-spec-with-counts [c1])))
    (is (= [(assoc g1 :group-name :c1-g1 :count 4)]
             (core/expand-group-spec-with-counts [{c1 2}])))
    (is (= [(assoc g1 :group-name :c2-c1-g1 :count 8)
            (assoc g2 :group-name :c2-g2 :count 6)]
             (core/expand-group-spec-with-counts {c2 2})))))

(deftest component-test
  (let [testfn (session-pipeline testfn {} (assoc :x 1))]
    (is (= 1
           (:x (second (core/process-lift-arguments
                        (test-session
                         {:components {'check-arguments-map testfn}}))))))))

(defn seen-phase-fn
  [name]
  (let [a (atom nil)]
    [(fn [session]
       (reset! a true)
       [nil session])
     (fn [] @a)]))

(deftest lift-all-node-set-test
  (let [local (group-spec "local")
        localhost (node-list/make-localhost-node :group-name "local")
        service (compute/compute-service "node-list" :node-list [localhost])]
    (testing "without all-node-set"
      (let [[localf seen?] (seen-fn "lift-test")]
        (lift
         local
         :phase (localf)
         :user (assoc utils/*admin-user*
                 :username (test-utils/test-username) :no-sudo true)
         :compute service)
        (is (seen?))))
    (testing "all-node-set (sequential)"
      (let [[localf seen?] (seen-fn "lift-test")
            [pf seen-phase?] (seen-phase-fn "lift-test")
            session (lift
                     nil
                     :all-node-set [local]
                     :phase (phase/phase-fn
                             pf
                             (localf))
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username) :no-sudo true)
                     :compute service
                     :environment {:algorithms {:lift-fn sequential-lift}})]
        (is (seen-phase?))
        (is (not (seen?)))))
    (testing "all-node-set (parallel)"
      (let [[localf seen?] (seen-fn "lift-test")
            [pf seen-phase?] (seen-phase-fn "lift-test")
            session (lift
                     nil
                     :all-node-set [local]
                     :phase (phase/phase-fn
                             pf
                             (localf))
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username) :no-sudo true)
                     :compute service
                     :environment {:algorithms {:lift-fn parallel-lift}})]
        (is (seen-phase?))
        (is (not (seen?)))))))
