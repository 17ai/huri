(ns huri.core
  (:require [huri.schema :refer [defcoercer]]
            (plumbing [core :refer [distinct-by distinct-fast sum map-vals
                                    map-from-vals map-from-keys fn->> for-map
                                    safe-get]]
                      [map :refer [safe-select-keys]])
            [clj-time.core :as t]
            [net.cgrand.xforms :as x]
            [clojure.data.priority-map :refer [priority-map-by]]
            [clojure.math.numeric-tower :refer [ceil expt round]]
            [cheshire.core :as json]
            (schema [core :as s])            
            [clojure.java.io :as io])
  (:import org.joda.time.DateTime))

(defn mapply
  [f coll]
  (map (partial apply f) coll))

(defn ensure-seq
  [x]
  (if (coll? x)
    x
    [x]))

(defn fsome
  [f]
  (fn [& args]
    (when (every? some? args)
      (apply f args))))

(defn transpose
  [m]
  (apply map vector m))

(defn any-of
  [& keyfns]
  {:combinator some-fn
   :keyfns keyfns})

(defn every-of
  [& keyfns]
  {:combinator every-pred
   :keyfns keyfns})

(s/defschema Pred (s/pred ifn?))

(s/defschema KeyFn (s/pred ifn?))

(s/defschema KeyCombinator {:combinator (s/pred ifn?)
                            :keyfns [KeyFn]})

(s/defschema Filters {KeyCombinator Pred})

(defcoercer ->keyfn KeyFn
  [x]
  (if (keyword? x)
    #(safe-get % x)
    x))

(defcoercer ->pred Pred
  [x]
  (cond                             
    (vector? x) #(apply (first x) % (rest x))
    (ifn? x) x
    :else (partial = x)))

(defcoercer ->key-combinator KeyCombinator
  [x]
  (if (s/check KeyCombinator x)
    {:combinator identity
     :keyfns [x]}
    x))

(defcoercer ->filters Filters
  [x]
  (if (map? x)
    x
    {identity x}))

(defn col
  [k df]
  (map (->keyfn k) df))

(defn where
  [filters df]
  (into (empty df)
    (->> (for [[{:keys [combinator keyfns]} pred] (->filters filters)]
           (apply combinator (map (partial comp pred) keyfns)))
         (apply every-pred)
         filter)
    df))

(defn rollup
  ([groupfn f df]
   (into (sorted-map) 
     (x/by-key (->keyfn groupfn) (comp (x/into []) (map f)))       	
     df))
  ([groupfn f keyfn df]
   (rollup groupfn (comp f (partial col keyfn)) df)))

(def rollup-vals (comp vals rollup))

(defn window
  ([summary-fn df]
   (window summary-fn identity df))
  ([summary-fn keyfn df]
   (window 1 summary-fn keyfn df))
  ([lag summary-fn keyfn df]
   (let [xs (col keyfn df)]
     (map summary-fn (drop lag xs) xs))))

(defn size
  [df]
  [(count df) (count (first df))])

(def cols (comp keys first))

(defn summary
  ([summary-fn]
   (partial summary summary-fn))
  ([summary-fn df]
   (for-map [[as [f k filters]] (map-vals ensure-seq summary-fn)]
     as (summary f (or k identity) (cond->> df filters (where filters)))))
  ([summary-fn keyfn df]
   (if (vector? summary-fn)
     (map #(summary % keyfn df) summary-fn)     
     (apply summary-fn (map #(col % df) (ensure-seq keyfn))))))

(defn update-cols
  [update-fns df]
  (map (apply comp (for [[k f] update-fns]
                     #(update % k f)))
       df))

(defn assoc-cols
  [new-cols df]
  (map (apply comp (for [[k f] new-cols]
                     #(assoc % k (f %))))
       df))

(defn derive-cols
  [new-cols df]
  (map (apply comp (for [[k [f & cols]] new-cols]
                     #(assoc % k (apply f (map (comp % ->keyfn) cols)))))
       df))

(defn ->data-frame
  [cols xs]
  (if (and (not= (count cols) (count (first xs)))
           (some coll? (first xs)))    
    (->data-frame cols (map (partial mapcat ensure-seq) xs))
    (map (partial zipmap cols) xs)))

(defn mask
  [cols df]
  (map #(safe-select-keys % cols) df))

(defn join
  [left right [left-key right-key] & {:keys [inner-join?]}]
  (let [left-key (->keyfn left-key)
        index (map-from-vals (->keyfn right-key) right)]
    (for [row left
          :when (index (left-key row) (not inner-join?))]
      (merge row (index (left-key row))))))

(def count-where (comp count where))

(defn count-distinct
  ([df]
   (count (distinct-fast df)))
  ([keyfn df]
   (count (distinct-by (->keyfn keyfn) df))))

(defn safe-divide
  [& denominators]
  (when (not-any? zero? denominators)
    (double (apply / denominators))))

(defn share
  ([filters]
   (partial share filters))
  ([filters df]
   (safe-divide (count-where filters df) (count df)))
  ([keyfn filters df]
   (safe-divide (summary sum keyfn (where filters df))
                (summary sum keyfn df))))

(defn distribution
  ([df]
   (distribution identity df))
  ([keyfn df]
   (distribution keyfn (constantly 1) df))
  ([keyfn weightfn df]
   (let [fs (rollup keyfn sum weightfn df)]
     (into (priority-map-by >)
       (x/by-key (map (partial * (summary (comp safe-divide sum) val fs))))
       fs))))

(defn harmonic-mean
  [xs]
  (double (/ (count xs) (summary sum / xs))))

(def cdf (fn->> distribution
                (sort-by key)
                (reductions (fn [[_ acc] [k v]]
                              [k (+ acc v)]))
                (into (sorted-map))))

(defn smooth
  [window xs]
  (sequence (x/partition window 1 (x/reduce x/avg)) xs))

(defn growth
  [b a]
  (safe-divide (- b a) a)) 

(defn sample
  [n xs]
  (into (empty xs)
    (take n)
    (shuffle (seq xs))))

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
  [xs]
  [(apply min xs) (apply max xs)])

(defn clamp
  ([[lower upper] x]
   (clamp lower upper x))
  ([lower upper x]
   (max (min x upper) lower)))

(defn quarter
  [dt]
  (ceil (/ (t/month dt) 3)))

(defn week
  [dt]
  (.getWeekOfWeekyear dt))

(defn date
  [dt]
  (t/floor dt t/day))

(defn year-month
  [dt]
  (t/floor dt t/month))

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

(defn between?
  [this start end]
  (t/within? start end this))

(defn before-now?
  [dt]
  (t/before? dt (t/now)))

(defn after-now?
  [dt]
  (t/after? dt (t/now)))

(defn spit-json
  [f x]
  (json/encode-stream x (io/writer f)))

(defn slurp-json
  [f]
  (json/decode-stream (io/reader f) true))
