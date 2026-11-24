import { Link } from 'react-router-dom'
import { ArrowRight, ArrowUp } from 'lucide-react'
import CustomerBrand from './CustomerBrand.jsx'
import '../styles/home.css'

export default function CustomerFooter(){
 return <footer className="c-footer c-footer-expanded"><div className="c-footer-inner">
  <div className="c-footer-invite"><div><h2>Bạn cần gửi hàng?</h2><p>Tạo đơn và theo dõi tiến độ ngay trên tài khoản.</p></div><Link to="/send" className="c-btn">Tạo đơn lấy & giao<ArrowRight size={19}/></Link></div>
  <div className="c-footer-columns"><div className="c-footer-about"><CustomerBrand tagline={false}/><p>Dịch vụ lấy và giao hàng tại TP. Hồ Chí Minh.</p></div>
   <div><h3>Liên hệ hỗ trợ</h3><a href="tel:0849870552">Hotline: 0849 870 552</a><a href="mailto:volamgiakiet@gmail.com">volamgiakiet@gmail.com</a><p>Khi liên hệ, vui lòng cung cấp mã đơn.</p></div>
   <div><h3>Gửi hàng cùng GK</h3><Link to="/service#phuc-vu">Khu vực & thời gian phục vụ</Link><Link to="/service#cuoc-phi">Cách tính cước</Link><Link to="/#cau-hoi">Câu hỏi thường gặp</Link></div>
   <div><h3>Điều khoản & chính sách</h3><Link to="/service#dieu-khoan">Điều khoản dịch vụ</Link><Link to="/service#bao-mat">Chính sách bảo mật</Link><Link to="/service#ho-tro">Hỗ trợ đơn hàng</Link></div></div>
  <div className="c-footer-base"><span>© {new Date().getFullYear()} GK · Dịch vụ lấy và giao hàng.</span><button type="button" onClick={()=>window.scrollTo({top:0,behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'})}>Về đầu trang<ArrowUp size={15}/></button></div>
 </div></footer>
}
