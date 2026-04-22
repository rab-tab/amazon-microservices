-- ═══════════════════════════════════════════════════════════════════════
-- Database Migration: Add Payment Tracking Fields (if not exist)
-- ═══════════════════════════════════════════════════════════════════════
-- File: V3__add_payment_tracking_fields.sql
-- Location: order-service/src/main/resources/db/migration/
-- ═══════════════════════════════════════════════════════════════════════

-- Add payment tracking fields (use IF NOT EXISTS to avoid errors if already present)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_failure_reason VARCHAR(500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_fraud_score INTEGER;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_transaction_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_retryable BOOLEAN;

-- Create index for payment_id (if not exists)
CREATE INDEX IF NOT EXISTS idx_orders_payment_id ON orders(payment_id);

-- Add comments
COMMENT ON COLUMN orders.payment_failure_reason IS 'Reason why payment failed (e.g., Insufficient funds, Fraud detected)';
COMMENT ON COLUMN orders.payment_fraud_score IS 'Fraud detection score from 0-100 (higher = more suspicious)';
COMMENT ON COLUMN orders.payment_transaction_id IS 'Transaction ID from payment gateway';
COMMENT ON COLUMN orders.payment_retryable IS 'Whether the payment failure can be retried';