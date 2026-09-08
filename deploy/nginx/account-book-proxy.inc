# Common reverse-proxy directives for MyTallyBook API locations.
# Install as: /etc/nginx/snippets/account-book-proxy.inc

proxy_pass http://127.0.0.1:7631;
proxy_http_version 1.1;

proxy_set_header Host $server_name;
proxy_set_header X-Real-IP $remote_addr;
# Nginx is the only trusted public proxy, so overwrite any client supplied value.
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Host $server_name;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Port $server_port;
proxy_set_header X-Request-Id $request_id;
# Clear alternative forwarding headers supplied by an untrusted client.
proxy_set_header Forwarded "";
proxy_set_header X-Forwarded-Prefix "";
proxy_set_header X-Forwarded-Ssl "";
proxy_set_header Connection "";

proxy_connect_timeout 3s;
proxy_send_timeout 30s;
proxy_read_timeout 30s;
proxy_intercept_errors off;
proxy_hide_header X-Powered-By;
