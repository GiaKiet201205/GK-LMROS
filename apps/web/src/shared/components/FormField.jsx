import { useId } from 'react'
export default function FormField({label,error,required=true,className='',...props}){
 const id=useId()
 return <div className={`field ${className}`}><label htmlFor={id}>{label}{required&&<span className="required-mark" aria-hidden="true"> *</span>}</label><input {...props} id={id} required={required} aria-invalid={!!error} aria-describedby={error?`${id}-error`:undefined}/>{error&&<p id={`${id}-error`} className="field-error" role="alert">{error}</p>}</div>
}
