(ns huri.core
  (:require (plumbing [core :refer [distinct-fast map-vals safe-get for-map
                                    map-from-vals indexed]]
                      [map :refer [safe-select-keys]])
            [clj-time.core :as t]
            [net.cgrand.xforms :as x]
            [clojure.data.priority-map :refer [priority-map-by]]
            [clojure.math.numeric-tower :refer [ceil expt round]]
            [cheshire.core :as json]            
            [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [clojure.spec :as s]
            [clojure.spec.test :as s.test])
  (:import org.joda.time.DateTime))

(defn mapply
  ([f]
   (map (partial apply f)))
  ([f coll & colls]
   (apply sequence (mapply f) coll colls)))

(defmacro for-cat
  [& for-body]
  `(apply concat (for ~@for-body)))

(defmacro with-conformer
  [x & tagvals]
  `(s/conformer (fn [[tag# ~x]]
                  (case tag# ~@tagvals))))

(defn fsome
  [f]
  (fn [& args]
    (when (every? some? args)
      (apply f args))))

(defn transpose
  [m]
  (apply map vector m))

(defn val-or-seq
  [element-type]
  (s/and
   (s/or :seq (s/coll-of element-type)
         :val element-type)
   (with-conformer x
     :seq x
     :val [x])))

(def ensure-seq (partial s/conform (val-or-seq ::s/any)))

(s/def ::keyfn (s/and
                (s/or :kw keyword?
                      :fn ifn?)
                (with-conformer x
                  :kw #(safe-get % x)
                  :fn x)))

(def ->keyfn (partial s/conform ::keyfn))

(s/def ::combinator fn?)

(s/def ::keyfns (s/+ ::keyfn))

(s/def ::key-combinator (s/and
                         (s/or :combinator (s/keys :req [::combinator ::keyfns])
                               :keyfn ::keyfn)
                         (with-conformer x
                           :combinator x
                           :keyfn {::combinator identity
                                   ::keyfns [x]})))

(s/def ::pred (s/and
               (s/or :vec (s/and vector? (s/cat :f ifn? :args (s/* ::s/any)))
                     :fn ifn?
                     :val (complement ifn?))
               (with-conformer x
                 :vec #(apply (:f x) % (:args x))
                 :fn x
                 :val (partial = x))))

(s/def ::filters (s/and
                  (s/or :map (s/map-of ::key-combinator ::pred
                                       :conform-keys true)
                        :pred (complement map?))
                  (with-conformer x
                    :map x
                    :pred (s/conform ::filters {identity x}))))

(s/def ::dataframe (s/nilable (s/every map?)))

(defn col
  ([k]
   (map (->keyfn k)))
  ([k df]
   (sequence (col k) df)))

(defn any-of
  [& keyfns]
  {::combinator some-fn
   ::keyfns keyfns})

(defn every-of
  [& keyfns]
  {::combinator every-pred
   ::keyfns keyfns})

(s/fdef where
  :args (s/cat :filters ::filters :df (s/nilable coll?))
  :ret coll?)

(defn where
  [filters df]
  (into (empty df)
    (->> (for [[{:keys [::combinator ::keyfns]} pred]
               (s/conform ::filters filters)]
           (apply combinator (map (partial comp pred) keyfns)))
         (apply every-pred)
         filter)
    df))

(s/def ::summary-fn
  (s/map-of keyword? (s/or :vec (s/cat :f ifn?
                                       :keyfns (val-or-seq ::keyfn)
                                       :filter (s/? ::filters))
                           :fn fn?)))

(s/fdef summary
  :args (s/alt :curried ::summary-fn
               :fn-map (s/cat :f ::summary-fn :df (s/nilable coll?))
               :fn (s/cat :f ifn? :keyfn (val-or-seq ::keyfn)
                          :df (s/nilable coll?)))
  :ret ::s/any)

(defn summary
  ([f]
   (partial summary f))
  ([f df]
   (map-vals (comp (fn [[f k filters]]
                     (summary f (or k identity)
                              (cond->> df filters (where filters))))
                   ensure-seq)
             f))
  ([f keyfn df]
   (apply f (map #(col % df) (ensure-seq keyfn)))))

(s/fdef rollup
  :args (s/alt :simple (s/cat :groupfn ::keyfn
                              :f (s/or :summary ::summary-fn :fn fn?)
                              :df (s/nilable coll?))
               :keyfn (s/cat :groupfn ::keyfn
                             :f (s/or :summary ::summary-fn :fn fn?)
                             :keyfn ::keyfn
                             :df (s/nilable coll?)))
  :ret (s/and map? sorted?))

(defn rollup
  ([groupfn f df]
   (into (sorted-map) 
     (x/by-key (->keyfn groupfn) (comp (x/into [])
                                       (map (if (map? f)
                                              (summary f)
                                              f))))       	
     df))
  ([groupfn f keyfn df]
   (rollup groupfn (comp f (partial col keyfn)) df)))

(def rollup-vals (comp vals rollup))
(def rollup-keep (comp (partial remove nil?) rollup-vals))
(def rollup-cat (comp (partial apply concat) rollup-vals))

(s/def ::fuse-fn (s/and (s/or :map map?
                              :kw keyword?
                              :fn ifn?)
                        (with-conformer x
                          :map x
                          :kw {x x}
                          :fn {::group x})))

(s/fdef rollup-fuse
  :args (s/alt :curried (s/cat :groupfn ::fuse-fn :f ::summary-fn)
               :full (s/cat :groupfn ::fuse-fn :f ::summary-fn
                            :df (s/nilable coll?)))
  :ret coll?)

(defn rollup-fuse
  ([groupfn f]
   (partial rollup-fuse groupfn f))
  ([groupfn f df]
   (let [groupfn (s/conform ::fuse-fn groupfn)]
     (rollup-vals (apply juxt (map ->keyfn (vals groupfn)))
                  (merge f (map-vals #(comp % first) groupfn))
                  df))))

(s/fdef rollup-transpose
  :args (s/alt :curried (s/cat :indexfn ::keyfn :f ::summary-fn)
               :full (s/cat :indexfn ::keyfn :f ::summary-fn
                            :df (s/nilable coll?)))
  :ret map?)

(defn rollup-transpose
  ([indexfn f]
   (partial rollup-transpose indexfn f))
  ([indexfn f df]
   (->> df
        (rollup indexfn f)
        (reduce-kv (fn [acc idx kvs]
                     (reduce-kv (fn [acc k v]
                                  (update acc k conj [idx v]))
                                acc
                                kvs))
                   (map-vals (constantly (sorted-map)) f)))))

(s/fdef window
  :args (s/alt :simple (s/cat :f ifn? :df (s/nilable coll?))
               :keyfn (s/cat :f ifn? :keyfn ::keyfn :df (s/nilable coll?))
               :lag (s/cat :lag pos-int? :f ifn? :keyfn ::keyfn
                           :df (s/nilable coll?)))
  :ret coll?)

(defn window
  ([f df]
   (window f identity df))
  ([f keyfn df]
   (window 1 f keyfn df))
  ([lag f keyfn df]
   (let [xs (col keyfn df)]
     (map f (drop lag xs) xs))))

(s/fdef size
  :args (s/every coll?)
  :ret (s/cat :rows int? :cols int?))

(defn size
  [df]
  [(count df) (count (first df))])

(s/fdef cols
  :args (s/cat :df ::dataframe)
  :ret coll?)

(defn cols
  [df]
  (keys (first df)))

(defn col-oriented
  [df]
  (for-map [k (cols df)]
    k (col k df)))

(defn row-oriented
  [m]
  (apply map (comp (partial zipmap (keys m)) vector) (vals m)))

(s/def ::col-transforms
  (s/map-of (val-or-seq keyword?)
            (s/or :vec (s/and vector? (s/cat :f ifn? :keyfns (s/+ ::keyfn)))
                  :ifn ifn?)
            :conform-keys true))

(s/fdef derive-cols
  :args (s/cat :new-cols ::col-transforms :df ::dataframe)
  :ret ::dataframe)

(defn derive-cols
  [new-cols df]
  (map (->> new-cols
            (s/conform ::col-transforms)
            (map (fn [[ks [tag f]]]
                   (let [f (if (= :vec tag)
                             (let [{:keys [f keyfns]} f]
                               (comp (partial apply f) (apply juxt keyfns)))
                             f)]
                     (fn [row]
                       (assoc-in row ks (f row))))))
            (apply comp))
       df))

(defn update-cols
  [update-fns df]
  (derive-cols (for-map [[k f] update-fns]
                 k [f k])
               df))

(defn ->data-frame
  [cols xs]
  (if (and (not= (count cols) (count (first xs)))
           (some coll? (first xs)))    
    (->data-frame cols (map (partial mapcat ensure-seq) xs))
    (map (partial zipmap cols) xs)))

(defn select-cols
  [cols df]
  (map #(safe-select-keys % cols) df))

(defn join
  [left right on & {:keys [inner-join?]}]
  (let [[lkey rkey] (if (sequential? on)
                      on
                      [on on])
        left->right (comp (map-from-vals (->keyfn rkey) right)
                          (->keyfn lkey))]
    (for [row left
          :when (or (left->right row) (not inner-join?))]
      (merge row (left->right row)))))

(defn count-where
  ([filters]
   (partial count-where filters))
  ([filters df]
   (count (where filters df))))

(defn count-distinct
  ([df]
   (count (distinct-fast df)))
  ([keyfn df]
   (count-distinct (col keyfn df))))

(defn safe-divide
  [numerator & denominators]
  (when (or (and (seq denominators) (not-any? zero? denominators))
            (and (not (zero? numerator)) (empty? denominators)))
    (double (apply / numerator denominators))))

(s/fdef sum
  :args (s/alt :coll (s/nilable coll?)
               :keyfn (s/cat :keyfn ::keyfn :df (s/nilable coll?)))
  :ret number?)

(defn sum
  ([df]
   (sum identity df))
  ([keyfn df]
   (transduce (col keyfn) + df)))

(defn rate
  ([keyfn-a keyfn-b]
   (partial rate keyfn-a keyfn-b))
  ([keyfn-a keyfn-b df]
   (safe-divide (sum keyfn-a df)
                (sum keyfn-b df))))

(defn share
  ([filters]
   (partial share filters))
  ([filters df]
   (safe-divide (count-where filters df) (count df)))
  ([filters weightfn df]   
   (safe-divide (sum weightfn (where filters df))
                (sum weightfn df))))

(defn distribution
  ([df]
   (distribution identity {} df))
  ([keyfn df]
   (distribution keyfn {} df))
  ([keyfn opts df]
   (let [{:keys [weightfn limit cutoff]
          :or {weightfn (constantly 1)}} opts]
     (when-let [norm (safe-divide (sum weightfn df))]
       (let [d (into (priority-map-by >)
                 (rollup keyfn (comp (partial * norm) sum) weightfn df))]
         (if-let [cutoff (or cutoff (some-> limit dec (drop d) first val))]
           (let [[d other] (split-with (comp (partial <= cutoff) val) d)]
             (cond-> (into (priority-map-by >) d)
               (not-empty other) (assoc :other (sum val other))))
           d))))))

(s/fdef mean
  :args (s/alt :coll (s/nilable coll?)
               :keyfn (s/cat :keyfn ::keyfn :df (s/nilable coll?))
               :weightfn (s/cat :keyfn ::keyfn :weightfn ::keyfn
                                :df (s/nilable coll?)))
  :ret number?)

(defn mean
  ([df]
   (mean identity df))
  ([keyfn df]
   (some->> (transduce (col keyfn) x/avg df) double))
  ([keyfn weightfn df]
   (let [keyfn (->keyfn keyfn)
         weightfn (->keyfn weightfn)]
     (rate #(* (keyfn %) (weightfn %)) weightfn df))))

(defn harmonic-mean
  ([df]
   (harmonic-mean identity df))
  ([keyfn df]
   (double (/ (count df) (sum (comp / keyfn) df)))))

(defn cdf
  ([df]
   (->> df
        distribution
        (sort-by key <)     
        (reductions (fn [[_ acc] [x y]]
                      [x (+ y acc)]))))
  ([keyfn df]
   (cdf (col keyfn df))))

(defn smooth
  [window xs]
  (sequence (x/partition window 1 (x/reduce x/avg)) xs))

(defn growth
  [b a]
  (safe-divide (- b a) a)) 

(defn threshold
  [min-size xs]
  (when (>= (count xs) min-size)
    xs))

(defn decay
  [lambda t]
  (expt Math/E (- (* lambda t))))

(defn round-to
  [precision x]
  (let [scale (/ precision)]
    (/ (round (* x scale)) scale)))

(defn extent
  ([xs]
   (let [[x & xs] xs]
     (r/fold (r/monoid (if (instance? org.joda.time.DateTime x)
                         (fn [[acc-min acc-max] x]
                           [(t/earliest acc-min x) (t/latest acc-max x)])
                         (fn [[acc-min acc-max] x]
                           [(min acc-min x) (max acc-max x)]))
                       (constantly [x x]))             
             xs)))
  ([keyfn df]
   (extent (col keyfn df))))

(defn clamp
  ([bounds]
   (partial clamp bounds))
  ([[lower upper] x]
   (clamp lower upper x))
  ([lower upper x]
   (max (min x upper) lower)))

(defn nil->0
  [x]
  (or x 0))

(defn quarter-of-year
  [dt]
  (ceil (/ (t/month dt) 3)))

(defn quarter
  [dt]
  (t/date-time (t/year dt) (inc (* (dec (quarter-of-year dt)) 3))))

(defn date
  [dt]
  (t/floor dt t/day))

(defn year-month
  [dt]
  (t/floor dt t/month))

(defn week-of-year
  [dt]
  (.getWeekOfWeekyear dt))

(defn week
  [dt]
  (t/minus (date dt) (t/days (dec (t/day-of-week dt)))))

(defn day-of-year
  [dt]
  (inc (t/in-days (t/interval (t/floor dt t/year) dt))))

(defn after?
  [this & that]
  (t/after? this (if (instance? org.joda.time.DateTime (first that))
                   (first that)
                   (apply t/date-time that))))

(defn before?
  [this & that]
  (t/before? this (if (instance? org.joda.time.DateTime (first that))
                    (first that)
                    (apply t/date-time that))))

(defn before-now?
  [dt]
  (t/before? dt (t/now)))

(defn after-now?
  [dt]
  (t/after? dt (t/now)))

(def not-before? (complement before?))
(def not-after? (complement after?))

(defn between?
  [this start end]
  (t/within? (t/interval start end) this))

(defn in?
  ([dt y]
   (= (t/year dt) y))
  ([dt y m]
   (= (year-month dt) (t/date-time y m))))

(defn since?
  [this p]
  (not-before? this (t/minus (if (#{org.joda.time.Years org.yoda.time.Months}
                                  (class p))
                               (year-month (t/now))
                               (date (t/now)))
                             p)))

(defn spit-json
  ([f x]
   (spit-json f {} x))
  ([f {:keys [cast-fns]} x]
   (json/encode-stream (cond->> x
                         cast-fns (update-cols cast-fns))
                       (io/writer f))))

(defn slurp-json
  [f]
  (json/decode-stream (io/reader f) true))

(s.test/instrument)
