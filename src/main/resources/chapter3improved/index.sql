ALTER TABLE ch3_orders
    ADD INDEX idx_order_date_status_amount (order_date, status, total_amount DESC);

ALTER TABLE ch3_order_stats
    ADD INDEX idx_total_amount (total_amount);