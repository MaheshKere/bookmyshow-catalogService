@echo off
setlocal

echo ==========================================
echo Resetting BookMyShow databases...
echo ==========================================

echo.
echo [1/2] Clearing Catalog DB...
podman exec bookmyshow-postgres psql -v ON_ERROR_STOP=1 -U catalog_user -d catalog_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

if errorlevel 1 (
    echo.
    echo ERROR: Failed to reset Catalog DB.
    echo Check that the container name is bookmyshow-postgres and it is running.
    pause
    exit /b 1
)

echo.
echo [2/2] Clearing Booking DB...
podman exec booking-postgres psql -v ON_ERROR_STOP=1 -U booking_user -d booking_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

if errorlevel 1 (
    echo.
    echo ERROR: Failed to reset Booking DB.
    echo Check that the container name is booking-postgres and it is running.
    pause
    exit /b 1
)

echo.
echo ==========================================
echo SUCCESS: Both databases are now empty.
echo Restart Catalog and Booking services.
echo Flyway will recreate the schemas/tables.
echo Then run the Postman dummy-data collection.
echo ==========================================

pause
endlocal
