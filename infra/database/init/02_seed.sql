-- Minimal first-install directory. Full test scenarios are loaded explicitly
-- after Logistics has applied its migrations: scripts/ops/reset_demo_data.py.
SET timezone TO 'Asia/Ho_Chi_Minh';
INSERT INTO warehouses(code,name,lat,lng,color) VALUES
 ('BT','GK · Bình Tân',10.7650,106.6030,'#0f766e'),
 ('TD','GK · Thủ Đức',10.8500,106.7720,'#115e59'),
 ('NB','GK · Nhà Bè',10.6950,106.7400,'#496864');
INSERT INTO vehicles(code,plate,warehouse_id,status)
SELECT 'XE-'||lpad(n::text,3,'0'),'51D-600.'||lpad(n::text,2,'0'),1+(n-1)/4,
       CASE WHEN n IN (4,8,10) THEN 'maintenance' ELSE 'available' END
FROM generate_series(1,12) n;
INSERT INTO drivers(code,name,phone,warehouse_id,vehicle_id,status)
SELECT 'TX-'||lpad(n::text,3,'0'),(ARRAY['Nguyễn Minh Quân','Trần Gia Bảo','Lê Đức Huy','Phạm Quốc An','Võ Thành Đạt','Bùi Hoàng Nam','Đặng Anh Tuấn','Huỳnh Minh Khang','Đỗ Trung Kiên','Ngô Thanh Tùng','Trần Gia Kiệt','Lê Hoàng Phúc','Nguyễn Đức Thành','Phạm Văn Long','Vũ Minh Khôi'])[n],
       CASE n WHEN 12 THEN '0903456789' ELSE '0903'||lpad(n::text,6,'0') END,
       1+(n-1)/5,
       CASE WHEN (n-1)%5=0 THEN 1+4*((n-1)/5)
            WHEN (n-1)%5=1 THEN CASE WHEN n=12 THEN 12 ELSE 2+4*((n-1)/5) END
            WHEN (n-1)%5=3 THEN CASE WHEN n=14 THEN 10 ELSE 4+4*((n-1)/5) END ELSE NULL END,'off'
FROM generate_series(1,15) n;
