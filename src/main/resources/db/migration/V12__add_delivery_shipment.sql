ALTER TABLE delivery ADD COLUMN carrier_code VARCHAR(30);
ALTER TABLE delivery ADD COLUMN carrier_name VARCHAR(100);
ALTER TABLE delivery ADD COLUMN tracking_number VARCHAR(100);
ALTER TABLE delivery ADD COLUMN shipment_issued_at TIMESTAMP WITH TIME ZONE;
