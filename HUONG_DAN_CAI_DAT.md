# Hướng dẫn cài đặt và cấu hình GK trên máy mới

Tài liệu này hướng dẫn chạy hệ thống bằng Docker. Phần giới thiệu chức năng, nghiệp vụ và công nghệ nằm trong [README.md](README.md).

Các lệnh bên dưới dùng **Bash**: Terminal trên Linux/macOS hoặc Ubuntu WSL trên Windows. Chạy từ thư mục GK chứa `docker-compose.yml`, trừ khi có ghi chú khác. Trên Linux, nếu Docker báo thiếu quyền, thêm `sudo` trước lệnh `docker`; không cần chạy toàn bộ trình soạn thảo bằng quyền root.

## 1. Chuẩn bị máy

- Có Internet để tải image, thư viện và sử dụng các dịch vụ địa chỉ/Google/AI nếu bật.
- Cài Docker và Docker Compose. Docker Desktop có kèm Compose; Linux có thể dùng Docker Engine và Compose plugin. Xem [hướng dẫn Docker Compose chính thức](https://docs.docker.com/compose/install/) và [Docker Engine cho Ubuntu](https://docs.docker.com/engine/install/ubuntu/).
- Trên Windows, dùng Docker Desktop với Linux containers và bật tích hợp WSL cho bản Ubuntu đang dùng.
- Cấu hình khởi điểm đề xuất: 4 CPU, RAM 8 GB và khoảng 30 GB ổ đĩa trống. Đây không phải mức bảo đảm chịu tải; có thể cần thêm RAM/ổ đĩa tùy dữ liệu. Nếu dùng Docker Desktop, kiểm tra tài nguyên cấp cho Docker.
- Image DB nghiệp vụ hiện là `timescale/timescaledb-ha:pg16`. Với máy ARM/Apple Silicon, kiểm tra image có hỗ trợ kiến trúc đó; lỗi `no matching manifest` cần xử lý image/platform trước khi chạy tiếp.

Chạy kiểm tra:

```bash
docker --version
docker compose version
docker info
```

Nếu dùng Docker Desktop, mở ứng dụng và đợi engine sẵn sàng. Trên Linux, cách cấu hình quyền không cần `sudo` có trong [tài liệu Docker](https://docs.docker.com/engine/install/linux-postinstall/).

**Không cần cài Java, Maven, Node.js, Python hoặc PostgreSQL lên máy host nếu chỉ chạy hệ thống bằng Docker.** Dockerfile sẽ chuẩn bị môi trường bên trong các container. Python trên host chỉ cần cho các script dữ liệu tùy chọn ở mục 9.

## 2. Chuẩn bị mã nguồn

Chép hoặc tải toàn bộ mã nguồn vào một thư mục, ví dụ `GK`, rồi mở terminal tại đó:

```bash
cd /duong-dan-den/GK
```

Thay đường dẫn bằng vị trí thực tế. Các thành phần cần có:

```text
GK/
├── .env.example
├── docker-compose.yml
├── apps/web/
├── services/auth/
├── services/customer/
├── services/logistics/
├── services/optimizer/
├── infra/
├── tools/prisma-studio/
├── scripts/
└── tests/
```

Không cần mang theo `node_modules`, `target`, `dist` hoặc cache. Khi giải nén, bảo đảm không bỏ sót file ẩn `.env.example` và các file cấu hình trong mã nguồn.

**Chép mã nguồn không đồng nghĩa chép dữ liệu.** Database nằm trong Docker volume của máy cũ. Nếu cần giữ đơn hàng, tài khoản và lịch sử, đọc mục 10 trước khi khởi tạo dữ liệu mẫu.

## 3. Tạo và chỉnh `.env`

Chỉ tạo file nếu máy mới chưa có `.env`:

```bash
cp -n .env.example .env
```

Mở `.env` bằng trình soạn thảo. Giữ các tên database/user/volume mặc định nếu đây là máy mới hoàn toàn; đổi các mật khẩu và token theo máy của bạn:

| Biến | Ý nghĩa | Cách điền |
|---|---|---|
| `COMPOSE_PROJECT_NAME` | Tên nhóm container | Mặc định `gk` |
| `DB_NAME`, `DB_USER`, `DB_PASSWORD` | DB nghiệp vụ | Tên/user mặc định `gk`; đặt mật khẩu riêng |
| `DB_PORT` | Cổng DB nghiệp vụ trên host | Mặc định `15433` |
| `AUTH_DB_NAME`, `AUTH_DB_USER`, `AUTH_DB_PASSWORD` | DB tài khoản admin/tài xế | Giữ tên/user mặc định, đặt mật khẩu riêng |
| `CUSTOMER_DB_NAME`, `CUSTOMER_DB_USER`, `CUSTOMER_DB_PASSWORD` | DB khách hàng | Giữ tên/user mặc định, đặt mật khẩu riêng |
| `DB_VOLUME_NAME`, `AUTH_DB_VOLUME_NAME`, `CUSTOMER_DB_VOLUME_NAME` | Vùng lưu dữ liệu ba DB | Giữ tên mặc định để các lần chạy dùng lại dữ liệu |
| `ADMIN_PASSWORD` | Mật khẩu tạo admin lần đầu | Bắt buộc tự đặt; thiếu/rỗng sẽ không khởi động |
| `INTERNAL_SERVICE_TOKEN` | Token trao đổi giữa các backend, bao gồm Optimizer | Thay chuỗi mẫu bằng giá trị ngẫu nhiên riêng |
| `OPTIMIZER_FIXED_TRIP_COST` | Phí chuyến mặc định của Optimizer | Mặc định `35000`; phí admin đã lưu và gửi trong yêu cầu được ưu tiên |
| `ROUTING_ENABLED` | Tự động xét cập nhật tuyến | Mặc định `true` |
| `ROUTING_PROVIDER` | Nguồn khoảng cách cho tối ưu | `estimate` khi chưa có key Google, `google` khi đã cấu hình |

Tạo token nếu máy có OpenSSL:

```bash
openssl rand -hex 32
```

Nếu chưa có OpenSSL trên host, tạo bằng image Docker:

```bash
docker run --rm python:3.12-slim python -c 'import secrets; print(secrets.token_hex(32))'
```

Chép kết quả vào `INTERNAL_SERVICE_TOKEN`. Không dùng nguyên giá trị `replace-with-a-random-internal-token`.

Dùng mật khẩu/token không chứa khoảng trắng sẽ dễ cấu hình hơn. Với chuỗi có ký tự `$` hoặc `#`, dùng giá trị đặt trong dấu nháy đơn theo cú pháp `.env` của Compose. Không chia sẻ `.env` có mật khẩu/key lên kho mã nguồn công khai.

`ADMIN_PASSWORD` chỉ tạo tài khoản lần đầu, không đổi mật khẩu của admin đã có. Tương tự, thay `POSTGRES_PASSWORD` thông qua `.env` sau khi volume DB đã được khởi tạo không tự đổi mật khẩu bên trong PostgreSQL. Không thay tên volume để sửa lỗi đăng nhập vì có thể vô tình chuyển sang bộ dữ liệu khác.

## 4. Chạy lần đầu

Kiểm tra cấu hình mà không in các giá trị bí mật ra terminal:

```bash
docker compose config --quiet
```

Khởi động:

```bash
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
```

Lần đầu cần tải các image và build Java/React/Python nên có thể mất vài phút hoặc lâu hơn tùy máy và mạng. Nếu hết thời gian chờ, xem log ở mục 12 trước khi chạy lại.

Trên Linux, nếu thiếu quyền Docker:

```bash
sudo docker compose up -d --build --wait --wait-timeout 300
sudo docker compose ps
```

Docker tự chạy ba DB, Redis, Redpanda, các backend, gateway và ba frontend. Không cần mở thêm terminal chạy backend/frontend thủ công. Prisma Studio chỉ chạy khi được bật riêng.

Lần đầu tạo volume DB nghiệp vụ, PostgreSQL nạp schema và danh mục ban đầu từ `infra/database/init`. Khi backend khởi động, migration bổ sung các bảng và ràng buộc hiện hành. Thư mục init không được chạy lại mỗi lần mở máy.

## 5. Truy cập và đăng nhập

| Giao diện | Địa chỉ |
|---|---|
| Điều phối viên/admin | http://localhost:3000 |
| Tài xế | http://localhost:3001 |
| Khách hàng | http://localhost:3002 |
| Kiểm tra API qua gateway | http://localhost:8080/api/health |

Tài khoản trên bộ dữ liệu mới:

- **Admin:** tên đăng nhập `admin`, mật khẩu là `ADMIN_PASSWORD` đã đặt ở mục 3. Hệ thống không còn mật khẩu mặc định; phải điền `.env` trước khi chạy.
- **Tài xế:** tên đăng nhập là mã tài xế, ví dụ `TX-001`; mật khẩu khởi tạo là số điện thoại tương ứng trong hồ sơ, chỉ gồm chữ số. Xem hồ sơ từ cổng admin hoặc Auth DB; không đoán mật khẩu từ mã tài xế.
- **Khách hàng:** tự đăng ký ở cổng khách hoặc đăng nhập Google sau khi cấu hình mục 7.

Nếu khôi phục DB cũ, dùng tài khoản/mật khẩu trong bản sao lưu. Thay `.env` không đặt lại các tài khoản đã tồn tại.

Bộ khởi tạo có danh mục kho, xe, tài xế, nhưng không tự có toàn bộ đơn hàng, lịch làm việc và lịch sử doanh thu như máy cũ. Tài xế cần đăng ký lịch, điều phối kiểm tra xe và đơn đủ điều kiện trước khi phân chuyến.

## 6. Google Routes: tính quãng đường và báo cước

Không có key vẫn có thể mở liên kết dẫn đường Google Maps, nhưng không được coi khoảng cách ước tính là quãng đường đường bộ đã xác nhận.

Khi có key có quyền dùng Google Routes API, chỉnh:

```dotenv
GOOGLE_ROUTES_API_KEY=KEY_CUA_BAN
ROUTING_PROVIDER=google
GOOGLE_MATRIX_MAX_POINTS=100
```

Google Cloud cần bật Routes API và cấu hình quyền, thanh toán/quota phù hợp cho dự án. Key được dùng ở backend, không dán vào mã React. Giới hạn key theo dịch vụ và môi trường backend sử dụng.

- Logistics sử dụng key để lấy quãng đường cho báo cước.
- Optimizer dùng ma trận Google khi `ROUTING_PROVIDER=google`.
- `GOOGLE_MATRIX_MAX_POINTS` giới hạn số điểm để kiểm soát khối lượng yêu cầu; có thể cần chia nhỏ phạm vi tính khi vượt giới hạn.

Khi chưa dùng Google, giữ `ROUTING_PROVIDER=estimate` và để key trống. Giá/khoảng cách phải được đọc theo trạng thái tạm tính của giao diện.

Áp dụng thay đổi:

```bash
docker compose up -d logistics optimizer
```

Sửa `.env` cần tạo lại container bằng `up -d`; chỉ `restart` không nạp lại environment mới.

## 7. Đăng nhập/đăng ký khách bằng Google

Thực hiện theo [hướng dẫn Google Identity Services](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid): tạo OAuth Client ID loại **Web application**. Trong **Authorized JavaScript origins**, thêm riêng từng dòng:

```text
http://localhost
http://localhost:3002
```

Luồng hiện tại dùng popup và callback JavaScript nên không cần tự đặt URL redirect. Khi sử dụng tên miền HTTPS, thêm đúng origin của cổng khách. Khi ứng dụng Google ở chế độ thử nghiệm, thêm các Gmail thử vào danh sách Test users nếu được yêu cầu.

Chỉnh `.env`:

```dotenv
CUSTOMER_GOOGLE_CLIENT_ID=CLIENT_ID_CUA_BAN.apps.googleusercontent.com
```

Áp dụng:

```bash
docker compose up -d customer
```

Cần **Client ID**, không dùng Client Secret, key Gemini hoặc key Maps thay thế. Khách đăng nhập Google lần đầu cần bổ sung số điện thoại theo luồng tài khoản. Khi Client ID chưa được cấu hình, nút Google không hiển thị.

## 8. Trợ lý GK: Gemini hoặc OpenAI

Chỉ cần cấu hình phần này nếu muốn bật trả lời bằng mô hình AI. Nếu không, giữ `GK_ASSISTANT_ENABLED=false`.

Ví dụ dùng Gemini:

```dotenv
GK_ASSISTANT_ENABLED=true
GK_ASSISTANT_PROVIDER=gemini
GEMINI_API_KEY=KEY_GEMINI_CUA_BAN
```

`GEMINI_MODEL` phải là tên model mà key/dự án của bạn được quyền sử dụng. Giá trị mẫu trong `.env.example` không bảo đảm khả dụng với mọi tài khoản; điều chỉnh theo nhà cung cấp.

Nếu dùng OpenAI:

```dotenv
GK_ASSISTANT_ENABLED=true
GK_ASSISTANT_PROVIDER=openai
OPENAI_API_KEY=KEY_OPENAI_CUA_BAN
```

Đặt `GK_ASSISTANT_MODEL` theo model tài khoản hỗ trợ. Chỉ cần cấu hình nhà cung cấp đã chọn, không bắt buộc có cả hai key.

Áp dụng:

```bash
docker compose up -d logistics
```

Kiểm tra từ trợ lý trên cổng admin. Key ở backend; không đặt trong biến frontend. Hướng dẫn tích hợp này không có nghĩa API AI được sử dụng miễn phí.

## 9. Xem database và tạo dữ liệu thử

### Prisma Studio

```bash
docker compose --profile tools up -d --build prisma-studio prisma-studio-auth prisma-studio-customer
```

| Database | Địa chỉ Studio |
|---|---|
| Logistics: đơn, kho, xe, tài xế, tài chính | http://localhost:5555 |
| Auth: tài khoản admin/tài xế | http://localhost:5556 |
| Customer: khách hàng, yêu cầu đặt đơn | http://localhost:5557 |

Studio chỉ mở trên localhost của máy chạy Docker. Chọn đúng database và schema `public`. Hai bảng benchmark/ETA cũ, nếu có, nằm trong schema lưu trữ `gk_legacy_archive`; tài khoản nhân viên không nằm trong DB nghiệp vụ.

### Dữ liệu thử tùy chọn

Chỉ thực hiện sau khi backend đã lên và chạy hết migration. Cần Python 3 trên host. Xem trước bộ dữ liệu, chưa thay DB:

```bash
python3 scripts/ops/reset_demo_data.py
```

Nếu đây là bộ DB thử có thể thay toàn bộ, nạp dữ liệu bằng:

```bash
python3 scripts/ops/reset_demo_data.py --reset
```

Trên Linux nếu script cần quyền Docker, dùng `sudo python3 ...` cho lệnh này. **`--reset` thay dữ liệu cả ba database**, không phải bước khởi động thông thường; không chạy sau khi vừa khôi phục dữ liệu cần giữ. Script có sao lưu trước khi thay dữ liệu, lưu ở thư mục anh em `GK-backups/demo-reset` nếu thư mục dự án tên `GK`.

Script `scripts/ops/seed_finance_history.py` phục vụ tạo lịch sử tài chính mô phỏng từ tháng 9/2026, cần thêm `psycopg[binary]` và công cụ PostgreSQL trên host. Đây là dữ liệu thử, không phải nhập giao dịch thật; không bắt buộc cho lần chạy đầu.

## 10. Mang dữ liệu từ máy cũ

Cần chuyển **cả ba DB**, không chỉ DB nghiệp vụ. Mã nguồn, `.env` và volume là các phần riêng biệt.

### Sao lưu trên máy cũ

Các lệnh sau chỉ dừng ứng dụng và xuất bản sao lưu, không xóa dữ liệu. Dùng cùng một terminal Bash để giữ biến đường dẫn:

```bash
gk_backup_dir="../GK-backups/chuyen-may-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$gk_backup_dir"
chmod 700 "$gk_backup_dir"

docker compose --profile tools stop frontend driver-frontend customer-frontend gateway auth customer logistics optimizer prisma-studio prisma-studio-auth prisma-studio-customer

docker compose exec -T db sh -c 'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$gk_backup_dir/logistics.dump"
docker compose exec -T auth-db sh -c 'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$gk_backup_dir/auth.dump"
docker compose exec -T customer-db sh -c 'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$gk_backup_dir/customer.dump"

chmod 600 "$gk_backup_dir"/*.dump
```

Không nhập thêm dữ liệu bằng công cụ khác trong lúc xuất bản sao lưu. Nếu lệnh nào lỗi, chưa coi bộ backup hoàn tất. Kiểm tra cả ba archive:

```bash
docker compose exec -T db pg_restore --list < "$gk_backup_dir/logistics.dump"
docker compose exec -T auth-db pg_restore --list < "$gk_backup_dir/auth.dump"
docker compose exec -T customer-db pg_restore --list < "$gk_backup_dir/customer.dump"
```

Sau khi sao lưu xong, có thể mở lại máy cũ:

```bash
docker compose up -d --wait --wait-timeout 300
```

Chuyển ba file dump sang máy mới. `.env` có thể được chuyển riêng bằng kênh riêng tư hoặc tạo lại từ mẫu; ghi lại PostgreSQL major version và phiên bản các extension của DB nguồn để chuẩn bị môi trường khôi phục tương thích.

### Khôi phục trên máy mới

1. Chuẩn bị mã nguồn, `.env` và image DB tương thích với máy cũ.
2. Chỉ khởi động các DB bằng `docker compose up -d --wait db auth-db customer-db`. Không mở backend, Studio hay nạp dữ liệu mẫu trong lúc khôi phục.
3. Dùng `pg_restore` khôi phục từng archive vào đúng DB. Kiểm tra rõ DB đích mới có thể thay dữ liệu trước khi sử dụng các tùy chọn làm sạch như `--clean`.
4. Nếu DB nghiệp vụ có TimescaleDB/hypertable, dùng quy trình khôi phục tương ứng, bao gồm `timescaledb_pre_restore()` và `timescaledb_post_restore()` theo [tài liệu TimescaleDB](https://github.com/timescale/docs/blob/latest/self-hosted/backup-and-restore/index.md). Không áp dụng máy móc lệnh restore PostgreSQL thường lên DB TimescaleDB.
5. Sau khi cả ba DB được khôi phục thành công, chạy `docker compose up -d --build --wait --wait-timeout 300`. Các migration chưa có trong backup được backend áp dụng khi khởi động.
6. Kiểm tra tài khoản, tổng số đơn, xe/tài xế và báo cáo tài chính so với máy cũ trước khi dùng tiếp.

Phần này không cung cấp một lệnh restore ghi đè chung cho mọi bản backup: cách làm sạch DB đích và phục hồi extension phụ thuộc cấu trúc DB nguồn. Giữ ba archive nguyên vẹn cho đến khi kiểm tra xong. Chép thư mục dự án hoặc đổi tên volume không thay thế việc khôi phục dữ liệu.

## 11. Các lần chạy sau và cập nhật mã nguồn

Mở máy, bảo đảm Docker đã chạy, vào thư mục GK:

```bash
docker compose up -d --wait --wait-timeout 300
```

Khi có mã nguồn mới:

```bash
docker compose up -d --build --wait --wait-timeout 300
```

Khi chỉ cập nhật một phần:

```bash
# Web khách hàng
docker compose up -d --build customer-frontend

# Backend nghiệp vụ, web admin và app tài xế
docker compose up -d --build logistics frontend driver-frontend
```

Tải lại trình duyệt bằng Ctrl + Shift + R. Với PWA còn giữ bản cũ, đóng/mở lại hoặc kiểm tra Service Worker/cache của trang.

Dừng tạm thời:

```bash
docker compose stop
```

Dừng và gỡ container/network, giữ volume dữ liệu:

```bash
docker compose down
```

**Không thêm `-v` nếu muốn giữ dữ liệu**: `docker compose down -v` xóa các volume do Compose quản lý. Cũng không xóa thủ công các volume `gk_dbdata`, `gk_authdata`, `gk_customerdata` đang sử dụng.

## 12. Kiểm tra lỗi thường gặp

Xem trạng thái và log:

```bash
docker compose ps
docker compose logs --tail=100 logistics auth customer optimizer gateway
```

| Hiện tượng | Cách kiểm tra/xử lý |
|---|---|
| `permission denied` tại `/var/run/docker.sock` | Linux: thêm `sudo` cho lệnh Docker hoặc cấu hình quyền theo tài liệu Docker |
| `Cannot connect to the Docker daemon` | Mở Docker Desktop hoặc khởi động Docker Engine |
| `port is already allocated` / `address already in use` | Kiểm tra tiến trình chiếm cổng; dừng đúng tiến trình hoặc đổi cổng host của dịch vụ trong Compose |
| Cổng DB xung đột | Đổi `DB_PORT` trong `.env`; không cần đổi cổng nội bộ `5432` |
| `password authentication failed` sau khi sửa `.env` | Volume đã có mật khẩu cũ; khôi phục cấu hình đúng hoặc đổi mật khẩu trong PostgreSQL, không xóa volume để thử |
| Admin không đăng nhập bằng mật khẩu mới trong `.env` | `ADMIN_PASSWORD` không đặt lại tài khoản đã có |
| Không thấy nút Google | Kiểm tra `CUSTOMER_GOOGLE_CLIENT_ID`, tạo lại container `customer` |
| Google báo `origin_mismatch` | Kiểm tra origin có đúng giao thức, hostname và cổng đang truy cập |
| Không tính được km Google | Kiểm tra Routes API, key, quota/billing và log Logistics/Optimizer; phân biệt với chế độ `estimate` |
| Trợ lý AI lỗi | Kiểm tra provider, key, quyền model và quota; tạo lại `logistics` sau khi sửa `.env` |
| Trang báo không tìm thấy chức năng / 502 sau cập nhật | Kiểm tra backend khỏe; nếu cần, `docker compose restart gateway` để làm mới kết nối |
| Tài xế/xe không được phân chuyến | Kiểm tra lịch, trạng thái nguồn lực, kho, giờ hẹn, tải trọng và số đơn; không chỉ kiểm tra container |
| Không có đơn hoặc số liệu khác máy cũ | Mã nguồn không chứa volume máy cũ; kiểm tra đã khôi phục đúng cả ba DB và đúng tên volume chưa |

Các cổng host đang dùng gồm `3000`, `3001`, `3002`, `8080`, `6379`, `9092` và DB nghiệp vụ mặc định `15433`. Studio dùng thêm `5555`, `5556`, `5557` khi được bật. Optimizer không công bố cổng 8000 ra host; Logistics gọi `http://optimizer:8000` trong mạng Docker và gửi `X-Internal-Token`. Nếu chạy Optimizer trực tiếp để phát triển, phải cấu hình `INTERNAL_SERVICE_TOKEN` và gửi header này khi gọi `/solve`.

## 13. Truy cập từ điện thoại và chia sẻ demo

### Cùng Wi-Fi

Dùng IP LAN của máy chạy Docker, ví dụ `192.168.1.10`, thay cho `localhost`:

- Admin: `http://192.168.1.10:3000`
- Tài xế: `http://192.168.1.10:3001`
- Khách hàng: `http://192.168.1.10:3002`

Cho phép các cổng giao diện qua firewall khi cần. IP ở trên chỉ là ví dụ. `localhost` trên điện thoại là chính điện thoại đó. HTTP qua IP LAN có thể hạn chế GPS, PWA và các API yêu cầu ngữ cảnh bảo mật; dùng HTTPS khi cần kiểm tra đầy đủ.

### Link Internet tạm thời trên Linux

Khi hệ thống đã chạy, mở terminal riêng:

```bash
sudo docker run --rm --network host cloudflare/cloudflared:latest tunnel --url http://127.0.0.1:3002
```

Cloudflare in link dạng `https://...trycloudflare.com` trong terminal; gửi link đó để thử cổng khách. Muốn chia sẻ tài xế/admin thì chạy tunnel riêng đến cổng `3001`/`3000`. Cách dùng Quick Tunnel được mô tả trong [tài liệu Cloudflare](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

Lệnh `--network host` ở đây dành cho Linux. Máy cần tiếp tục bật, có mạng; Docker và tunnel phải chạy. Dừng bằng Ctrl + C; chạy lại thường nhận link khác. Người test làm việc trên DB đang dùng. Đăng nhập Google cần origin tương ứng được cho phép; không mặc định hoạt động trên mọi link demo mới. Chỉ chia sẻ link admin với người được quyền quản trị.
