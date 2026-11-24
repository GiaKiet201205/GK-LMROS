import { Link } from 'react-router-dom'

export default function CustomerBrand({ tagline=true }) { return <Link className="c-brand" to="/" aria-label="GK · Trang chủ"><span className="c-monogram">GK<span /></span><span><b>GK</b>{tagline&&<small>Gửi gọn. Giao đúng.</small>}</span></Link> }
