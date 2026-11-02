-- Runs once, on the payment-db container's first boot. Gives integration
-- tests their own database (payments_test_db) alongside the dev one
-- (payments_db), so `./gradlew test` never touches -- or gets confused by
-- -- data you created manually while running the app from Eclipse.
CREATE DATABASE payments_test_db OWNER payments;
