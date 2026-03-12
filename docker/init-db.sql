-- Create databases
CREATE DATABASE users_db;
CREATE DATABASE products_db;
CREATE DATABASE orders_db;
CREATE DATABASE payments_db;

-- Grant permissions
GRANT ALL PRIVILEGES ON DATABASE users_db TO amazon;
GRANT ALL PRIVILEGES ON DATABASE products_db TO amazon;
GRANT ALL PRIVILEGES ON DATABASE orders_db TO amazon;
GRANT ALL PRIVILEGES ON DATABASE payments_db TO amazon;
