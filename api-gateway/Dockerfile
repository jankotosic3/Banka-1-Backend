# Use the official lightweight NGINX Alpine image
FROM nginx:alpine

# Copy our template into the official NGINX templates folder
COPY api-gateway/default.conf.template /etc/nginx/templates/default.conf.template

# Expose port 80 to the Docker network
EXPOSE 80