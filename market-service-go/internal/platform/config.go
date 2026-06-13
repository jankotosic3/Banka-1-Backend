package platform

import (
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	ServerPort string
	GRPCPort   string
	DBHost     string
	DBPort     string
	DBName     string
	DBUser     string
	DBPassword string
	DBSSLMode  string
	JWT        JWTConfig
	CORS       CORSConfig
	Stock      StockConfig
	FX         FXConfig
}

type JWTConfig struct {
	Secret           string
	Issuer           string
	IDClaim          string
	RolesClaim       string
	PermissionsClaim string
}

type CORSConfig struct {
	AllowedOrigins []string
	AllowedMethods []string
}

type StockConfig struct {
	MarketDataBaseURL      string
	MarketDataAPIKey       string
	AlphaVantageAPIKey     string
	ExchangeServiceBaseURL string
	InfluxEnabled          bool
	InfluxURL              string
	InfluxOrg              string
	InfluxBucket           string
	InfluxToken            string
	RedisHost              string
	RedisPort              string
	RedisTimeout           time.Duration
	RefreshEnabled         bool
	RefreshInterval        time.Duration
	PriceFeedTTL           time.Duration
	ExchangeCSVLocation    string
}

type FXConfig struct {
	TwelveDataBaseURL    string
	TwelveDataAPIKey     string
	MarginPercentage     string
	CommissionPercentage string
	FetchOnStartup       bool
	FetchCron            string
	SupportedCurrencies  []string
}

func LoadConfig() Config {
	return Config{
		ServerPort: getEnv("SERVER_PORT", "8085"),
		GRPCPort:   getEnv("GRPC_PORT", "9085"),
		DBHost:     getEnv("MARKET_SERVICE_DB_HOST", "postgres"),
		DBPort:     getEnv("MARKET_SERVICE_DB_PORT", "5432"),
		DBName:     getEnv("MARKET_SERVICE_DB_NAME", "market_service"),
		DBUser:     getEnv("MARKET_SERVICE_DB_USER", "postgres"),
		DBPassword: getEnv("MARKET_SERVICE_DB_PASSWORD", "postgres"),
		DBSSLMode:  getEnv("MARKET_SERVICE_DB_SSLMODE", getEnv("POSTGRES_SSLMODE", "disable")),
		JWT: JWTConfig{
			Secret:           getEnv("JWT_SECRET", "development_market_service_secret_123456"),
			Issuer:           getEnv("BANKA_SECURITY_ISSUER", getEnv("BANKA_SECURITY_ISSUER_FALLBACK", "banka1")),
			IDClaim:          getEnv("BANKA_SECURITY_ID_CLAIM", "id"),
			RolesClaim:       getEnv("BANKA_SECURITY_ROLES_CLAIM", "roles"),
			PermissionsClaim: getEnv("BANKA_SECURITY_PERMISSIONS_CLAIM", "permissions"),
		},
		CORS: CORSConfig{
			AllowedOrigins: splitCSV(getEnv("BANKA_SECURITY_CORS_ALLOWED_ORIGINS", "http://localhost:4200")),
			AllowedMethods: []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"},
		},
		Stock: StockConfig{
			MarketDataBaseURL:      getEnv("STOCK_MARKET_DATA_BASE_URL", "https://www.alphavantage.co"),
			MarketDataAPIKey:       getEnv("STOCK_MARKET_DATA_API_KEY", getEnv("ALPHA_VANTAGE_API_KEY", "")),
			AlphaVantageAPIKey:     getEnv("ALPHA_VANTAGE_API_KEY", ""),
			ExchangeServiceBaseURL: getEnv("STOCK_EXCHANGE_SERVICE_URL", "http://localhost:8085"),
			InfluxEnabled:          getEnvBool("STOCK_INFLUX_ENABLED", false),
			InfluxURL:              getEnv("STOCK_INFLUX_URL", "http://localhost:8086"),
			InfluxOrg:              getEnv("STOCK_INFLUX_ORG", "banka1"),
			InfluxBucket:           getEnv("STOCK_INFLUX_BUCKET", "market_data"),
			InfluxToken:            getEnv("STOCK_INFLUX_TOKEN", ""),
			RedisHost:              getEnv("REDIS_HOST", ""),
			RedisPort:              getEnv("REDIS_PORT", "6379"),
			RedisTimeout:           time.Duration(getEnvInt("REDIS_TIMEOUT_MS", 2000)) * time.Millisecond,
			RefreshEnabled:         getEnvBool("STOCK_LISTING_REFRESH_ENABLED", true),
			RefreshInterval:        time.Duration(getEnvInt("STOCK_LISTING_REFRESH_INTERVAL_MS", 900000)) * time.Millisecond,
			PriceFeedTTL:           time.Duration(getEnvInt("STOCK_PRICE_CACHE_TTL_SECONDS", 15)) * time.Second,
			ExchangeCSVLocation:    getEnv("STOCK_EXCHANGE_SEED_CSV_LOCATION", ""),
		},
		FX: FXConfig{
			TwelveDataBaseURL:    getEnv("EXCHANGE_TWELVE_DATA_BASE_URL", "https://api.twelvedata.com"),
			TwelveDataAPIKey:     getEnv("TWELVE_DATA_API_KEY", getEnv("ALPHA_VANTAGE_API_KEY", "")),
			MarginPercentage:     getEnv("EXCHANGE_MARGIN_PERCENTAGE", "1.0"),
			CommissionPercentage: getEnv("EXCHANGE_COMMISSION_PERCENTAGE", "0.70"),
			FetchOnStartup:       getEnvBool("EXCHANGE_RATES_FETCH_ON_STARTUP", true),
			FetchCron:            getEnv("EXCHANGE_RATES_FETCH_CRON", "0 0 8 * * *"),
			SupportedCurrencies:  []string{"RSD", "EUR", "CHF", "USD", "GBP", "JPY", "CAD", "AUD"},
		},
	}
}

func (c Config) DatabaseURL() string {
	return "postgres://" + c.DBUser + ":" + c.DBPassword + "@" + c.DBHost + ":" + c.DBPort + "/" + c.DBName + "?sslmode=" + c.DBSSLMode
}

func getEnv(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func getEnvBool(key string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func getEnvInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func splitCSV(value string) []string {
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}
