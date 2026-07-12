# Hướng dẫn GateShield (Tiếng Việt)

## 1. GateShield là gì?
GateShield là một lớp bảo vệ và điều phối cho các API của hệ thống. Nó hoạt động như một "cổng kiểm soát" giữa client và backend, giúp xác thực yêu cầu, giới hạn lưu lượng, điều hướng request tới đúng dịch vụ và ghi nhận hoạt động để quản trị viên theo dõi.

## 2. GateShield hoạt động như thế nào?
Khi một yêu cầu API tới hệ thống, GateShield sẽ thực hiện các bước sau:

1. Nhận request từ client.
2. Kiểm tra thông tin xác thực như API key hoặc token.
3. Xác định tenant và quyền truy cập của tenant đó.
4. Áp dụng các quy tắc như rate limiting, auth và route mapping.
5. Điều hướng request tới backend phù hợp.
6. Ghi log và giám sát các metric như latency, status và số request.

## 3. Tenant là gì?
Tenant là một đơn vị, khách hàng hoặc nhóm ứng dụng riêng biệt sử dụng hệ thống.

Mỗi tenant có thể có:
- API key riêng
- quyền truy cập riêng
- giới hạn request riêng
- route riêng hoặc policy riêng

## 4. Route là gì?
Route là quy tắc định tuyến cho một request API. Route quy định request sẽ được chuyển tới backend nào.

Ví dụ:
- request đến `/api/v1/hello` có thể được điều hướng tới mock backend
- request tới một endpoint khác có thể chuyển tới service khác

## 5. Vì sao GateShield quan trọng?
GateShield giúp:
- bảo vệ API khỏi truy cập trái phép
- giới hạn lưu lượng để tránh overload
- tách biệt quyền truy cập theo từng tenant
- tăng khả năng giám sát và vận hành ổn định
- cho phép cấu hình linh hoạt theo nhu cầu từng khách hàng hoặc từng API

## 6. Có thể cấu hình theo nhu cầu từng người không?
Có. GateShield cho phép điều chỉnh các chính sách theo tenant, route hoặc endpoint. Ví dụ:
- giới hạn số request mỗi phút
- bật/tắt auth cho một API
- gán route khác nhau cho từng tenant
- điều chỉnh mức độ bảo vệ cho từng service

## 7. Happy path (luồng hoạt động thành công)
Một request hợp lệ sẽ đi qua các bước sau:

1. Client gửi request kèm API key hợp lệ.
2. GateShield xác thực và kiểm tra quyền.
3. GateShield kiểm tra rate limit.
4. GateShield điều hướng request tới backend phù hợp.
5. Backend xử lý và trả về response.
6. GateShield ghi log và trả kết quả cho client.

## 8. Lệnh thực hiện nhanh
### 8.1 Khởi động hệ thống
```powershell
docker compose up -d
```

### 8.2 Kiểm tra trạng thái container
```powershell
docker compose ps
```

### 8.3 Gửi request tới gateway
```powershell
curl.exe -i -H "X-API-Key: gs_2691e14edb67ccd43405168d291447af790ae9c8c42226b846ab0f93d37a2125" http://localhost:8080/api/v1/hello
```

### 8.4 Chạy smoke test (nếu có script)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke_test.ps1 -GatewayBaseUrl "http://localhost:8080" -AdminToken "change-me"
```

### 8.5 Chạy frontend (nếu cần xem giao diện quản trị)
```powershell
cd frontend
npm install
npm run dev
```

## 9. Ghi chú quan trọng
- Đảm bảo các service đang chạy trước khi gọi API.
- Header đúng phải là `X-API-Key`.
- Nếu bạn dùng PowerShell, nên dùng `curl.exe` thay vì `curl` đơn thuần.
- Admin token và API key có thể được cấu hình khác nhau tùy môi trường.

## 10. Tóm tắt ngắn cho khách hàng
GateShield giống như một cổng kiểm soát thông minh cho API: nó bảo vệ, điều hướng, giám sát và giúp doanh nghiệp quản lý truy cập một cách an toàn, linh hoạt và có thể tùy chỉnh theo từng khách hàng hoặc từng dịch vụ.
