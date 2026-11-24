ALTER TABLE orders ADD CONSTRAINT order_weight_input CHECK(weight_kg BETWEEN 0.01 AND 10000) NOT VALID,
 ADD CONSTRAINT order_volume_input CHECK(volume_m3 BETWEEN 0.001 AND 100) NOT VALID,
 ADD CONSTRAINT order_coordinate_input CHECK(lat BETWEEN -90 AND 90 AND lng BETWEEN -180 AND 180 AND (pickup_lat IS NULL OR pickup_lat BETWEEN -90 AND 90) AND (pickup_lng IS NULL OR pickup_lng BETWEEN -180 AND 180)) NOT VALID,
 ADD CONSTRAINT order_window_input CHECK(tw_end>tw_start AND (pickup_from IS NULL OR pickup_to IS NULL OR pickup_to>pickup_from)) NOT VALID,
 ADD CONSTRAINT order_description_input CHECK(goods_description IS NULL OR length(trim(goods_description)) BETWEEN 2 AND 500) NOT VALID;
