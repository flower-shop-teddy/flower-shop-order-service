-- Bản chụp địa chỉ phải chứa được mọi giá trị mà sổ địa chỉ (customer-service) cho phép:
-- recipientName tối đa 150 ký tự, note tối đa 500 (V1 để 100 / 255 nên địa chỉ dài thì không đặt được đơn).
ALTER TABLE customer_order ALTER COLUMN recipient_name TYPE VARCHAR(150);
ALTER TABLE customer_order ALTER COLUMN address_note TYPE VARCHAR(500);
