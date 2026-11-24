import { nf } from '../api/client.js'
import { PRICE_STATUS } from '../lib/shipping.js'
export default function ShippingQuote({ pricing }) {
 const {quote,ready,busy,error,calculate}=pricing
 return <section className="shipping-quote" aria-label="Cước giao hàng"><div className="shipping-quote-head"><h3>Cước giao hàng</h3><button className="btn ghost" type="button" disabled={!ready||busy} onClick={calculate}>{busy?'Đang tính…':quote?'Tính lại cước':'Tính cước'}</button></div>
 {quote&&<><strong className="shipping-quote-amount">{quote.amountVnd==null?'Chờ báo giá':`${nf(quote.amountVnd)} đ`}</strong><span className="tag gray">{PRICE_STATUS[quote.status]}</span><p>{nf(quote.distanceMeters/1000,3)} km → {nf(quote.billableKm)} km tính cước{quote.rateVnd!=null&&` × ${nf(quote.rateVnd)} đ/km`}</p>{quote.amountVnd!=null&&quote.rateVnd!=null&&quote.amountVnd>quote.billableKm*quote.rateVnd&&<p>Cước tối thiểu: {nf(quote.amountVnd)} đ/đơn</p>}</>}
 {error&&<p className="callout red" role="alert">{error}</p>}
 </section>
}
