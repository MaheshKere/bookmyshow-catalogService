@echo off
setlocal

echo ==========================================
echo Resetting Catalog, Booking and Payment...
echo Identity DB and user accounts are preserved.
echo ==========================================
echo.
echo Stop Catalog, Booking and Payment services before running this script.
echo Kafka topics and consumer offsets are not reset.
echo Previously queued Kafka events may be processed after services restart.
echo.

rem Check all three connections before making any destructive changes.
echo Checking database connections...
podman exec bookmyshow-postgres psql -v ON_ERROR_STOP=1 -U catalog_user -d catalog_db -c "SELECT 1;" >nul
if errorlevel 1 goto catalog_error
podman exec booking-postgres psql -v ON_ERROR_STOP=1 -U booking_user -d booking_db -c "SELECT 1;" >nul
if errorlevel 1 goto booking_error
podman exec payment-postgres psql -v ON_ERROR_STOP=1 -U payment_user -d payment_db -c "SELECT 1;" >nul
if errorlevel 1 goto payment_error

rem Each schema reset is atomic within its own database. Identity is never targeted.
echo [1/3] Clearing Catalog DB...
podman exec bookmyshow-postgres psql -v ON_ERROR_STOP=1 --single-transaction -U catalog_user -d catalog_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
if errorlevel 1 goto catalog_error

echo.
echo [2/3] Clearing Booking DB...
podman exec booking-postgres psql -v ON_ERROR_STOP=1 --single-transaction -U booking_user -d booking_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
if errorlevel 1 goto booking_error

echo.
echo [3/3] Clearing Payment DB...
podman exec payment-postgres psql -v ON_ERROR_STOP=1 --single-transaction -U payment_user -d payment_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
if errorlevel 1 goto payment_error

echo.
echo ==========================================
echo SUCCESS: Catalog, Booking and Payment databases are now empty.
echo Identity DB, registered users and passwords were not changed.
echo Restart Catalog, Payment and Booking services.
echo Flyway will recreate their schemas/tables, including inbox and outbox.
echo Then run the Postman dummy-data collection.
echo ==========================================
pause
exit /b 0

:catalog_error
echo ERROR: Catalog DB connection or reset failed.
echo Check that bookmyshow-postgres is running and catalog_user can access catalog_db.
goto failed

:booking_error
echo ERROR: Booking DB connection or reset failed.
echo Check that booking-postgres is running and booking_user can access booking_db.
goto failed

:payment_error
echo ERROR: Payment DB connection or reset failed.
echo Check that payment-postgres is running and payment_user can access payment_db.
goto failed

:failed
echo Any databases already reset remain empty; there is no cross-database transaction.
echo Identity DB was not touched.
pause
exit /b 1