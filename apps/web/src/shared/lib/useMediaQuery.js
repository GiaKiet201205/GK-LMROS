import { useEffect, useState } from 'react'
export default function useMediaQuery(query){
 const [matches,setMatches]=useState(()=>window.matchMedia(query).matches)
 useEffect(()=>{const media=window.matchMedia(query),change=()=>setMatches(media.matches);change();if(media.addEventListener){media.addEventListener('change',change);return()=>media.removeEventListener('change',change)}media.addListener(change);return()=>media.removeListener(change)},[query])
 return matches
}
