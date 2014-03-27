(ns onyx.peer.multi-peer-mem-test
  (:require [midje.sweet :refer :all]
            [onyx.peer.hornetq-util :as hq-util]
            [onyx.api]))

(def n-messages 150)

(def batch-size 50)

(def timeout 500)

(def echo 1)

(def in-queue (str (java.util.UUID/randomUUID)))

(def out-queue (str (java.util.UUID/randomUUID)))

(def hornetq-host "localhost")

(def hornetq-port 5445)

(def hq-config {"host" hornetq-host "port" hornetq-port})

(hq-util/write-and-cap! hq-config in-queue (map (fn [x] {:n x}) (range n-messages)) echo)

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def catalog
  [{:onyx/name :in
    :onyx/direction :input
    :onyx/consumption :concurrent
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name in-queue
    :hornetq/host hornetq-host
    :hornetq/port hornetq-port
    :hornetq/batch-size batch-size
    :hornetq/timeout timeout}
   
   {:onyx/name :inc
    :onyx/fn :onyx.peer.multi-peer-mem-test/my-inc
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/timeout timeout}
   
   {:onyx/name :out
    :onyx/direction :output
    :onyx/consumption :concurrent
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name out-queue
    :hornetq/host hornetq-host
    :hornetq/port hornetq-port
    :onyx/batch-size batch-size
    :onyx/timeout timeout}])

(def workflow {:in {:inc :out}})

(def id (str (java.util.UUID/randomUUID)))

(def coord-opts {:datomic-uri (str "datomic:mem://" id)
                 :hornetq-host hornetq-host
                 :hornetq-port hornetq-port
                 :zk-addr "127.0.0.1:2181"
                 :onyx-id id
                 :revoke-delay 5000})

(def conn (onyx.api/connect (str "onyx:memory//localhost/" id) coord-opts))

(def peer-opts {:hornetq-host hornetq-host
                :hornetq-port hornetq-port
                :zk-addr "127.0.0.1:2181"
                :onyx-id id})

(def v-peers (onyx.api/start-peers conn 4 peer-opts))

(onyx.api/submit-job conn {:catalog catalog :workflow workflow})

(def results (hq-util/read! hq-config out-queue (inc n-messages) echo))

(try
  ;; (dorun (map deref (map :runner v-peers)))
  (finally
   (doseq [v-peer v-peers]
     (try
       ((:shutdown-fn v-peer))
       (catch Exception e (prn e))))
   (try
     (onyx.api/shutdown conn)
     (catch Exception e (prn e)))))

(let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
  (fact (set (butlast results)) => expected)
  (fact (last results) => :done))


(fact results => (conj (vec (map (fn [x] {:n (inc x)}) (range n-messages))) :done))

