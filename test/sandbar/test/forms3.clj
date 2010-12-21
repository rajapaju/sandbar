;; Copyright (c) Brenton Ashworth. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file COPYING at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns sandbar.test.forms3
  (:use [clojure.test :only [deftest testing is]]
        [sandbar.forms3]
        [sandbar.validation :only [build-validator
                                   non-empty-string]])
  (:require [net.cgrand.enlive-html :as html]))

;; ============
;; Test Helpers
;; ============

(defn <- [s] (html/html-resource (java.io.StringReader. s)))

(defn field-cell [t id]
  (let [field (-> (html/select t [id]) first :attrs :class keyword)
        cell (first
              (filter #(and (= (:tag %) :div)
                            (= (-> % :attrs :class) "field-cell"))
                      (html/select t [(html/has [id])])))]
    {:type field
     :cell cell}))

(defn cell-type-dispatch [m] (:type m))

(defmulti label* cell-type-dispatch)

(defmethod label* :default [m]
           (html/text (first (html/select (:cell m) [:div.field-label]))))

(defmulti required?* cell-type-dispatch)

(defmethod required?* :default [m]
           (not
            (empty?
             (html/select (:cell m) [:div.field-label :span.required]))))

(defmulti error-message* cell-type-dispatch)

(defmethod error-message* :default [m]
           (html/text
            (first
             (html/select (:cell m) [:div.field-error-message]))))

(defmulti error-visible?* cell-type-dispatch)

(defmethod error-visible?* :default [m]
           (not (-> (:cell m)
                    (html/select [:div.field-error-message])
                    first
                    :attrs
                    :style
                    (= "display:none;"))))

(defn id-selector [id]
  (keyword (str "#" (name id))))

(defn label [h id]
  (let [template (<- h)
        cell (field-cell template (id-selector id))]
    (label* cell)))

(defn attr [h id attr]
  (let [template (<- h)]
    (-> template
        (html/select [(id-selector id)])
        first
        :attrs
        attr)))

(defn required? [h id]
  (let [template (<- h)
        cell (field-cell template (id-selector id))]
    (required?* cell)))

(defn error-message [h id]
  (let [template (<- h)
        cell (field-cell template (id-selector id))]
    (error-message* cell)))

(defn error-visible? [h id]
  (let [template (<- h)
        cell (field-cell template (id-selector id))]
    (error-visible?* cell)))

(defn form-attrs [h id]
  (let [template (<- h)]
    (-> template
        (html/select [(id-selector id)])
        first
        :attrs)))

(defn exists? [h id]
  (let [template (<- h)]
    (not (-> template
             (html/select [(id-selector id)])
             empty?))))

(defn form-action [h id]
  (:action (form-attrs h id)))

(defn form-method [h id]
  (:method (form-attrs h id)))

(defn fields-and-vals [h id]
  (let [template (<- h)
        form (-> template
                 (html/select [(id-selector id)])
                 first)]
    (apply hash-map (flatten
                     (->> (html/select form [:input])
                          (map #(-> % :attrs))
                          (map #(vector (keyword (:name %)) (:value %))))))))

;; =====
;; Tests
;; =====

(deftest add-errors-tests
  (let [form (form :user-form)
        form-info {:request
                   {:flash
                    {:user-form {:errors {:name ["name err"]}}}}}
        processor (add-errors form)]
    (is (= (:errors (processor form-info))
           {:name ["name err"]}))
    (is (= (processor {})
           {:errors nil}))))

(deftest add-previous-input-tests
  (let [form (form :user-form)
        form-info {:errors {:name ["form err"]}
                   :request
                   {:flash
                    {:user-form {:data {:name "x"}}}}}
        processor (add-previous-input form)]
    (is (= (processor form-info)
           (merge form-info {:form-data {:name "x"}})))
    (is (= (processor (dissoc form-info :errors))
           (dissoc form-info :errors)))))

(deftest load-source-tests
  (testing "load source"
    (let [processor (load-source #(-> % :id))]
      (testing "load is called"
        (let [form-info {:params {:id 42}
                         :request {}}]
          (is (= (:form-data (processor form-info))
                 42))))
      (testing "load is not called when data is present"
        (let [request {:params {"id" "42"}}
              form-info {:form-data {:name "x"}
                         :request request}]
          (is (= (:form-data (processor form-info))
                 {:name "x"}))))
      (testing "returns nil when no data is loaded"
        (let [form-info {:request {}}]
          (is (nil? (:form-data (processor form-info)))))))))

(deftest defaults-tests
  (testing "defaults as map"
    (let [processor (defaults {:name "z"})]
      (testing "when form data exists"
        (let [form-info {:form-data {:name "x"}}]
          (is (= (:form-data (processor form-info))
                 {:name "x"}))))
      (testing "when form data does not exist"
        (let [form-info {}]
          (is (= (:form-data (processor form-info))
                 {:name "z"}))))))
  (testing "defaults as function"
    (let [processor (defaults (fn [request] {:name "z"}))]
      (testing "when form data does not exist"
        (let [form-info {}]
          (is (= (:form-data (processor form-info))
                 {:name "z"})))))))

(deftest form-view-tests
  (testing "form view"
    (testing "without processors"
      (let [fields []
            processor identity]
        (is (= (form-view {} fields processor)
               {:form-data nil
                :params {}
                :errors nil
                :request {}
                :response {}
                :fields fields
                :i18n {}}))
        (let [request {:params {"name" "x" "id" "42"}}]
          (is (= (form-view request fields processor)
                 {:form-data nil
                  :params {:name "x"
                           :id 42}
                  :errors nil
                  :request request
                  :response {}
                  :fields fields
                  :i18n {}})))))
    (testing "with processors"
      (let [form (form :user-form)
            fields []
            processors [(add-errors form)
                        (add-previous-input form)
                        (load-source (fn [params]
                                       (if-let [id (:id params)]
                                         {:name "y"
                                          :id (:id params)})))
                        (defaults {:name "z"})]
            view-processor (apply comp (reverse processors))]
        (testing "error"
          (let [request {:flash
                         {:user-form {:data {:name "x"}
                                      :errors {:name ["name err"]}}}}]
            (is (= (form-view request fields view-processor)
                   {:form-data {:name "x"}
                    :params {}
                    :errors {:name ["name err"]}
                    :request request
                    :response {}
                    :fields []
                    :i18n {}}))))
        (testing "edit"
          (let [params {"id" "42"}
                request {:flash {} :params params}]
            (is (= (form-view request fields view-processor)
                   {:form-data {:name "y" :id 42}
                    :params {:id 42}
                    :errors nil
                    :request request
                    :response {}
                    :fields []
                    :i18n {}}))))
        (testing "create"
          (let [request {}]
            (is (= (form-view request fields view-processor)
                   {:form-data {:name "z"}
                    :params {}
                    :errors nil
                    :request request
                    :response {}
                    :fields []
                    :i18n {}}))))))))

;;
;; Form Fields
;;

(deftest textfield-tests
  (let [h (render-field (textfield :name :id :name-) {})]
    (is (= (label h :name-) ""))
    (is (= (attr h :name- :size) "35"))
    (is (= (attr h :name- :name) "name"))
    (is (false? (required? h :name-)))
    (is (false? (error-visible? h :name-))))
  (let [field (textfield :name :id :name- :label "Name")]
    (let [h (render-field field {})]
      (is (= (label h :name-) "Name")))
    (let [h (render-field field {:form-data {:name "a"}})]
      (is (= (attr h :name- :value) "a")))
    (let [h (render-field field {:form-data {:name "a"}
                                 :errors {:name ["r"]}})]
      (is (= (attr h :name- :style) nil))
      (is (= (error-message h :name-) "r")))
    (let [h (render-field (textfield :name
                                     :id :name-
                                     :label "Name"
                                     :required true)
                          {:form-data {:name "a"}
                           :errors {:name ["name error"]}})]
      (is (= (attr h :name- :value) "a"))
      (is (true? (required? h :name-)))
      (is (true? (error-visible? h :name-)))
      (is (= (error-message h :name-) "name error")))))