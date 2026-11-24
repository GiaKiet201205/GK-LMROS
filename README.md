# GK — Hệ thống quản lý lấy hàng, giao hàng và điều phối vận tải

GK là đồ án xây dựng hệ thống quản lý giao nhận hàng hóa trong thành phố, kết nối **khách hàng, điều phối viên và tài xế**. Hệ thống quản lý quy trình từ khi khách đặt đơn, tài xế lấy hàng, bàn giao về kho, phân chuyến giao đến khi hoàn tất hoặc hoàn hàng về người gửi.

Mục tiêu của GK là giúp đơn vị vận chuyển tổ chức nguồn lực, giảm số chuyến và quãng đường không cần thiết, theo dõi trách nhiệm giao nhận và đánh giá hiệu quả kinh doanh từ dữ liệu được ghi nhận.

## 1. Vấn đề hệ thống giải quyết

| Vấn đề trong hoạt động giao nhận | Cách GK hỗ trợ |
|---|---|
| Đơn hàng phân tán, khó biết kho nào phụ trách | Tập trung đơn hàng và chọn kho gần điểm lấy hàng trong các kho đang hoạt động |
| Phân xe, tài xế bằng tay dễ trùng lịch hoặc vượt tải | Kiểm tra lịch làm việc, trạng thái nguồn lực, tải trọng, thể tích và số đơn trước khi phân công |
| Chia nhiều chuyến nhỏ hoặc sắp xếp điểm giao chưa hợp lý | Gom đơn phù hợp, cải thiện thứ tự điểm dừng và so sánh chi phí vận hành dự kiến |
| Có đơn phát sinh trong lúc xe đang chạy | Xét chèn đơn đủ điều kiện vào phần hành trình còn lại, giữ các điểm đã xử lý và điểm đang đi tới |
| Khách không biết hàng đang ở đâu và ai phụ trách | Hiển thị tiến độ, lịch sử, tài xế và biển số xe theo phân công thực tế |
| Giao không thành công nhưng thiếu quy trình xử lý tiếp | Ghi nhận lý do, quản lý về kho, giao lại và hoàn về người gửi |
| Chi phí nhiên liệu, bảo dưỡng và lương khó tổng hợp | Quản lý phiếu, quy trình duyệt và sổ thu chi liên kết với hồ sơ gốc |
| Khó đánh giá doanh thu và lợi nhuận theo kỳ | Tổng hợp doanh thu, chi phí đã ghi sổ, lợi nhuận và so sánh giữa các kỳ |

## 2. Đối tượng sử dụng và chức năng

### Khách hàng

- Đăng ký, đăng nhập bằng tài khoản GK hoặc Google khi dịch vụ được tích hợp.
- Tạo đơn lấy và giao hàng: người gửi, người nhận, địa chỉ, khung giờ, mô tả hàng, khối lượng, thể tích và bên thanh toán.
- Tìm và xác nhận địa chỉ để cập nhật tọa độ; kiểm tra vị trí qua Google Maps.
- Xem khoảng cách và cước trước khi đặt đơn; phân biệt quãng đường xác nhận với quãng đường ước tính.
- Theo dõi các mốc xử lý, tài xế và xe được phân công, thanh toán và thông báo liên quan đến đơn.
- Tra cứu thông tin dịch vụ, cách tính cước, điều khoản, chính sách bảo mật và liên hệ hỗ trợ.

Giao diện khách hàng thích ứng với desktop, tablet và điện thoại. Bản web desktop có banner và minh họa Three.js; giao diện cảm ứng và PWA ưu tiên thao tác gửi hàng, xem đơn và thông báo. Cảnh 3D là minh họa dịch vụ, không phải bản đồ theo dõi xe.

### Điều phối viên / quản trị viên

- Quản lý đơn hàng; tìm kiếm, lọc trạng thái, lọc quá hạn, hẹn lại nhiều đơn và xem chi tiết phân công.
- Thêm, sửa và ngừng sử dụng hồ sơ kho, xe, tài xế; giữ các liên kết và lịch sử nghiệp vụ cần thiết.
- Xem lịch làm việc, nguồn lực rảnh, đang được giữ chỗ, đang hoạt động hoặc bảo dưỡng.
- Lập phương án riêng cho lấy hàng, giao hàng và hoàn hàng; xem trước rồi xác nhận phân công.
- Theo dõi hành trình và tiến độ từng xe; lọc theo loại chuyến.
- Quản lý bảng cước, thông báo và các tùy chọn giao diện như sáng/tối.
- Duyệt phiếu nhiên liệu, chi phí xe và phiếu hoàn tất bảo dưỡng; xem lại chứng từ đã xử lý.
- Quản lý lương tài xế, sổ thu chi, báo cáo và so sánh hiệu quả kinh doanh theo kỳ.
- Sử dụng trợ lý GK để tra cứu hướng dẫn và một số dữ liệu vận hành theo quyền quản trị.

### Tài xế

- Sử dụng giao diện ưu tiên điện thoại và máy tính bảng.
- Đăng ký lịch làm việc theo tuần, tối thiểu 6 ngày; khung ca làm việc 08:00–20:00.
- Nhận thông báo phân chuyến và chủ động bấm bắt đầu khi xuất phát.
- Xem danh sách điểm theo thứ tự đã được điều phối; mở Google Maps đến từng điểm và gọi khách.
- Xác nhận lấy hàng, giao hàng, thất bại, hoàn hàng và bàn giao về kho.
- Xác nhận đã/chưa thanh toán theo bên trả cước trên đơn.
- Lập phiếu nhiên liệu, chi phí xe; báo sự cố, cập nhật lịch sửa và gửi phiếu sửa xong kèm số tiền, mã hóa đơn/phiếu sửa chữa.
- Xem kết quả duyệt phiếu, thông báo và bảng lương đã được duyệt.

## 3. Quy trình và ràng buộc nghiệp vụ

Quy trình chính:

**Khách tạo đơn → kho phụ trách tiếp nhận → phân chuyến lấy → tài xế lấy hàng → bàn giao về kho → phân chuyến giao → tài xế giao hàng → hoàn tất và về kho.**

Chuyến lấy, chuyến giao và chuyến hoàn được quản lý riêng. Hàng vừa lấy cần được bàn giao về kho trước khi đưa vào chuyến giao.

| Nhóm ràng buộc | Cách xử lý |
|---|---|
| Địa chỉ và thông tin đơn | Kiểm tra dữ liệu bắt buộc, số điện thoại, vị trí xác nhận, mô tả hàng, khối lượng, thể tích và khung giờ hợp lệ |
| Kho phụ trách | Chọn kho đang hoạt động gần điểm lấy hàng từ người gửi |
| Sức chứa xe | Mặc định tối đa 45 đơn/chuyến, đồng thời tuân thủ tải trọng và thể tích của từng xe |
| Nguồn lực | Không phân trùng xe/tài xế đã có chuyến liên quan hoặc không đủ điều kiện nhận chuyến |
| Giữ chỗ và xuất phát | Xem trước phương án chưa giữ nguồn lực; xác nhận mới giữ xe/tài xế; tài xế bắt đầu mới chuyển sang đang hoạt động |
| Lịch làm việc | Kiểm tra ngày làm và ca; từ 20:00 xét ngày kế tiếp, sau nửa đêm xét ngày hiện tại |
| Khung giờ khách hẹn | Kiểm tra khả năng phục vụ; đơn quá hạn cần được xử lý hẹn lại |
| Tiến độ chuyến | Giữ lịch sử đã thực hiện; hoàn tất bàn giao cuối chuyến và không còn phân công liên quan mới giải phóng nguồn lực |
| Giao thất bại | Ghi lý do, đưa hàng về kho trước khi phân giao lại; sau 3 lần giao thất bại chuyển sang hoàn người gửi |
| Bảo dưỡng | Xe bị giữ khi bảo dưỡng; tài xế gửi sửa xong, admin duyệt mới cho xe nhận chuyến trở lại |

Các cổng dùng bộ mốc tiến độ lấy–kho–giao thống nhất. Thông báo được lưu và hiển thị trong hệ thống theo đối tượng nhận; việc này không đồng nghĩa đã gửi SMS, email hoặc thông báo đẩy khi ứng dụng đóng.

## 4. Tối ưu phân chuyến và tuyến đường

Bộ tối ưu được viết bằng Python, sử dụng **heuristic chèn điểm, cải thiện thứ tự điểm dừng và điều chỉnh phân bổ giữa các tuyến**. Mục tiêu là tạo phương án hợp lệ, ưu tiên gom ít chuyến khi có thể, sau đó giảm quãng đường và chi phí vận hành.

- Khi toàn bộ đơn vừa sức chứa và đáp ứng thời gian, hệ thống ưu tiên gom chung thay vì chia thành nhiều chuyến nhỏ.
- Khi vượt số đơn, tải trọng, thể tích hoặc khả năng phục vụ, hệ thống chia và phân bổ đơn giữa các xe đủ điều kiện.
- Khi có đơn phát sinh, hệ thống xem xét phần tuyến chưa thực hiện và giữ các điểm đã hoàn tất, điểm đang đi tới.
- Phương án chưa xếp được đơn cần thể hiện lý do để điều phối xử lý.
- Khoảng cách có thể lấy từ Google Routes API; chế độ ước tính được phân biệt với dữ liệu đường bộ từ Google.

Chi phí dùng để so sánh phương án phân phối gồm:

**Chi phí vận hành dự kiến = phí cố định mỗi chuyến + quãng đường × chi phí/km của xe.**

Phí cố định giúp phản ánh chi phí mở thêm chuyến; chi phí/km giúp so sánh quãng đường và mức vận hành của từng xe. Đây là số liệu lập kế hoạch, không tự ghi thành khoản chi thực tế.

Thuật toán hướng tới phương án tốt trong thời gian tính toán giới hạn, không bảo đảm tìm được nghiệm tối ưu toàn cục.

## 5. Cước, thanh toán và tài chính

### Cước giao hàng

Cước khách hàng dựa trên quãng đường từ điểm lấy đến điểm giao, mức khối lượng và bảng giá hiện hành. Số km tính tiền được làm tròn đến số nguyên gần nhất: **20,4 km → 20 km; 20,5 km → 21 km**. Hệ thống hỗ trợ mức cước tối thiểu và trường hợp cần báo giá riêng.

Cước thu khách và chi phí vận hành xe là hai thông tin riêng: một bên là giá dịch vụ, một bên phục vụ đánh giá phương án vận chuyển.

### Thanh toán và doanh thu

Người gửi hoặc người nhận có thể là bên thanh toán. Tài xế xác nhận tình trạng thanh toán trong quá trình lấy/giao. Đơn giao thành công đủ điều kiện được ghi nhận doanh thu một lần; việc thu hoặc nộp tiền không tạo thêm doanh thu cho cùng đơn.

### Chi phí và lợi nhuận

Chi phí được quản lý qua lương tài xế, phiếu nhiên liệu, bảo dưỡng và các khoản chi được ghi sổ. Mức lương cố định có thể thay đổi theo tài xế và tháng hiệu lực.

Đối với bảo dưỡng, tài xế phải nhập kết quả sửa chữa, chi phí nguyên đồng từ 0 đến 1 tỷ đồng và mã hóa đơn/phiếu sửa chữa khi báo sửa xong. Admin duyệt mới ghi chi phí; bảo hành 0 đồng không phát sinh khoản chi. Phiếu đã duyệt chỉ được xem, có thể mở từ sổ thu chi.

**Lợi nhuận theo sổ = doanh thu đã ghi nhận − chi phí đã ghi sổ.**

Báo cáo không tiếp tục trừ phí vận hành ước tính khi đã ghi nhận chi phí thực tế, nhằm tránh tính trùng. Hệ thống tổng hợp số liệu theo ngày, tuần, tháng, quý, năm và hỗ trợ so sánh giữa các kỳ. Kết quả phụ thuộc mức độ đầy đủ của dữ liệu doanh thu, lương và chi phí đã được ghi nhận.

## 6. Kiến trúc hệ thống

GK được tổ chức theo mô hình microservice với **4 dịch vụ backend**, 3 giao diện và API Gateway:

| Thành phần | Trách nhiệm |
|---|---|
| API Gateway | Định tuyến yêu cầu từ giao diện đến các dịch vụ backend |
| Auth Service | Quản lý tài khoản, phiên đăng nhập và quyền của điều phối viên/tài xế |
| Customer Service | Quản lý khách hàng, đăng nhập Google, đặt đơn, tiến độ và thông báo phía khách |
| Logistics Service | Xử lý kho, xe, tài xế, đơn hàng, phân phối, hành trình, bảng cước, bảo dưỡng và tài chính |
| Optimizer Service | Tính phương án phân tuyến, kiểm tra ràng buộc và chèn đơn; không sở hữu DB nghiệp vụ |
| Ba giao diện | Cổng điều phối viên, tài xế và khách hàng, sử dụng các thành phần giao diện dùng chung |

Auth, Customer và Logistics sở hữu database riêng; không có khóa ngoại xuyên database. Các dịch vụ trao đổi qua API nội bộ. Đồng bộ tiến độ sang cổng khách hàng có cơ chế thử lại và chống xử lý trùng.

Redis hỗ trợ lưu vị trí gần nhất; Redpanda cung cấp hạ tầng tương thích Kafka để phát sự kiện. Hệ thống kết hợp API, worker đồng bộ và sự kiện, không chỉ giao tiếp qua Kafka. Logistics vẫn tập trung các nghiệp vụ vận tải và tài chính trong cùng dịch vụ để giữ các giao dịch liên quan nhất quán.

## 7. Ngôn ngữ, công nghệ và công cụ

| Nhóm | Công nghệ sử dụng | Vai trò |
|---|---|---|
| Ngôn ngữ | Java 17 | Backend Auth, Customer và Logistics |
| Ngôn ngữ | Python 3.12 | Thuật toán tối ưu, dịch vụ Optimizer và công cụ dữ liệu |
| Ngôn ngữ | JavaScript, JSX, HTML, CSS, SQL | Giao diện web, bố cục responsive, truy vấn và ràng buộc dữ liệu |
| Backend Java | Spring Boot 3.3, Spring Web, Spring JDBC | REST API, truy cập dữ liệu và xử lý giao dịch |
| Backend Python | FastAPI, Pydantic, Uvicorn, HTTPX | API tính tuyến, kiểm tra đầu vào và gọi dịch vụ khoảng cách |
| Frontend | React 18, React Router, Vite | Giao diện theo thành phần, điều hướng và đóng gói ba cổng |
| Trình bày | Lucide React, Three.js | Biểu tượng và minh họa 3D trên web desktop |
| Ứng dụng web | PWA, Service Worker, Web App Manifest | Hỗ trợ trải nghiệm cài đặt lên thiết bị; không phải ứng dụng native Android/iOS |
| Cơ sở dữ liệu | PostgreSQL, PostGIS | Lưu trữ nghiệp vụ, giao dịch và dữ liệu vị trí |
| Dữ liệu thời gian | TimescaleDB khi có extension | Hỗ trợ lưu điểm GPS theo thời gian |
| Cache và sự kiện | Redis, Redpanda/Kafka, Spring Kafka | Vị trí gần nhất và phát sự kiện nghiệp vụ |
| Địa chỉ và dẫn đường | Photon/OpenStreetMap, Google Routes API, liên kết Google Maps | Tìm tọa độ, tính khoảng cách và mở dẫn đường bên ngoài |
| Đăng nhập Google | Google Identity Services, xác minh token phía backend | Xác thực khách hàng và liên kết tài khoản |
| Trợ lý GK | Gemini hoặc OpenAI, tìm kiếm kiến thức theo ngữ cảnh | Hỗ trợ điều phối viên tra cứu hướng dẫn và dữ liệu được phép |
| Quản trị dữ liệu | Prisma Studio | Xem các database; không thay thế lớp JDBC hay migration của backend |
| Hạ tầng | Docker, Docker Compose, Nginx | Đóng gói dịch vụ, quản lý các thành phần và định tuyến API |
| Công cụ phát triển | Maven, Node.js/npm | Quản lý phụ thuộc và đóng gói mã nguồn |
| Kiểm thử | JUnit, Spring Boot Test, Node.js Test Runner, Python unittest | Kiểm tra nghiệp vụ, API, ràng buộc, tối ưu tuyến và dữ liệu thử |

Trợ lý GK sử dụng tài liệu và ngữ cảnh để hỗ trợ trả lời, không huấn luyện lại trọng số mô hình. Trợ lý không tự phân chuyến, thu tiền hoặc thay đổi dữ liệu nghiệp vụ.

## 8. Tổ chức dữ liệu và phạm vi đồ án

Dữ liệu được chia theo trách nhiệm dịch vụ:

- **Auth DB:** tài khoản và phiên của điều phối viên, tài xế.
- **Customer DB:** khách hàng, phiên đăng nhập, yêu cầu đặt đơn, lịch sử và thông báo phía khách.
- **Logistics DB:** danh mục nguồn lực, đơn hàng, lịch, chuyến, điểm dừng, tiến độ, bảng cước, phiếu bảo dưỡng, chi phí và sổ tài chính.

Database sử dụng khóa ngoại, ràng buộc, trigger và migration để bảo vệ trạng thái và lịch sử. Các khoản đã ghi sổ và phiếu đã duyệt có quy tắc bảo vệ dữ liệu; thông tin chi tiết được liên kết về đơn hoặc chứng từ gốc.

GK có bộ dữ liệu thử với tên, địa chỉ và các tình huống vận hành để đánh giá chức năng, thuật toán và báo cáo. Dữ liệu được tạo phục vụ đồ án là dữ liệu mô phỏng, không phải bằng chứng về giao dịch kinh doanh thực tế.

Phạm vi hiện tại tập trung vào giao nhận qua kho trong thành phố, hỗ trợ vận hành và theo dõi trên web/PWA. Bản đồ không được nhúng vào màn hình điều phối; hành trình thể hiện bằng danh sách điểm và timeline, còn dẫn đường mở qua Google Maps.
