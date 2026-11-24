import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

// A decorative GK miniature. No map tiles, tracking data or external models.
export function createJourneyScene(host, onUnavailable) {
 const canvas=document.createElement('canvas')
 const context=canvas.getContext('webgl2',{alpha:true,antialias:true,powerPreference:'low-power'})
 if(!context)return null
 let renderer
 try{renderer=new THREE.WebGLRenderer({canvas,context,alpha:true,antialias:true,powerPreference:'low-power'})}
 catch{context.getExtension('WEBGL_lose_context')?.loseContext();return null}
 renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,1.5))
 renderer.outputColorSpace=THREE.SRGBColorSpace
 renderer.toneMapping=THREE.ACESFilmicToneMapping
 renderer.toneMappingExposure=1.35
 renderer.shadowMap.enabled=true
 renderer.shadowMap.type=THREE.PCFSoftShadowMap
 canvas.setAttribute('aria-hidden','true')
 canvas.className='c-journey-canvas'
 host.appendChild(canvas)
 const scene=new THREE.Scene(),world=new THREE.Group()
 scene.add(world)
 const camera=new THREE.OrthographicCamera(-5,5,3.5,-3.5,.1,50)
 camera.position.set(8,8.5,11);camera.lookAt(0,.15,0)
 scene.add(new THREE.HemisphereLight(0xfffdf0,0x8ba997,2.7))
 const sun=new THREE.DirectionalLight(0xfff6df,3.2)
 sun.position.set(-3,8,5);sun.castShadow=true;sun.shadow.mapSize.set(1024,1024)
 Object.assign(sun.shadow.camera,{left:-5,right:5,top:5,bottom:-5,near:.5,far:20})
 sun.shadow.normalBias=.035;sun.shadow.bias=-.0003;scene.add(sun)
 const geometries=new Set(),materials=new Set(),textures=new Set()
 const release=()=>{geometries.forEach(g=>g.dispose());materials.forEach(m=>m.dispose());textures.forEach(t=>t.dispose());renderer.dispose();renderer.forceContextLoss();canvas.remove()}
 let cleanup=release
 try {
 function material(color,extra={}){const m=new THREE.MeshStandardMaterial({color,roughness:.86,...extra});materials.add(m);return m}
 const pine=material('#174f3f'),green=material('#286e55'),sage=material('#a7bdaa'),cream=material('#f4f0df'),tan=material('#d4a16a'),tape=material('#efdab6'),rust=material('#bb6542'),dark=material('#263f36'),windowMat=material('#9bb8ae')
 function mesh(geometry,mat,position=[0,0,0],parent=world){geometries.add(geometry);const m=new THREE.Mesh(geometry,mat);m.position.set(...position);m.castShadow=true;m.receiveShadow=true;parent.add(m);return m}
 function box(w,h,d,x,y,z,mat,parent=world,radius=.035){return mesh(new RoundedBoxGeometry(w,h,d,2,Math.min(radius,w/4,h/4,d/4)),mat,[x,y,z],parent)}
 function textMaterial(text,background='#174f3f',foreground='#fffdf1'){
  const image=document.createElement('canvas');image.width=512;image.height=256;const ctx=image.getContext('2d')
  ctx.fillStyle=background;ctx.fillRect(0,0,512,256);ctx.fillStyle=foreground;ctx.font='750 158px system-ui, sans-serif';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(text,256,138)
  const texture=new THREE.CanvasTexture(image);texture.colorSpace=THREE.SRGBColorSpace;textures.add(texture)
  const mat=new THREE.MeshBasicMaterial({map:texture});materials.add(mat);return mat
 }
 const gkSign=textMaterial('GK'),boxSign=textMaterial('GK','#f3e3c9','#1e5946')
 // A soft raised platform and a thin, continuous route around the warehouse.
 box(6.5,.22,4.65,0,-.19,0,material('#c5d4c4'),world,.1)
 box(6.38,.06,4.53,0,-.05,0,material('#e1e9dc'),world,.025)
 for(let i=-2;i<=2;i++)box(.014,.006,3.9,i*.95,-.014,0,material('#d3dfcf'))
 for(let i=-1;i<=1;i++)box(5.8,.006,.014,0,-.012,i*1.1,sage)
 const route=new THREE.CatmullRomCurve3([
  new THREE.Vector3(-2.55,.025,.4),new THREE.Vector3(-1.7,.025,1.55),new THREE.Vector3(.3,.025,1.65),new THREE.Vector3(2.4,.025,.9),new THREE.Vector3(2.55,.025,-.8),new THREE.Vector3(.7,.025,-1.7),new THREE.Vector3(-1.55,.025,-1.6)
 ],true,'catmullrom',.15)
 mesh(new THREE.TubeGeometry(route,100,.025,5,true),rust)
 // Warehouse: loading door, awning, GK sign and a roof with a restrained stripe.
 const warehouse=new THREE.Group();warehouse.position.set(0,0,-.62);world.add(warehouse)
 box(1.9,1.15,1.25,0,.56,0,pine,warehouse,.05)
 box(2.13,.12,1.46,0,1.19,0,cream,warehouse,.035)
 box(.08,.015,1.4,-.58,1.259,0,rust,warehouse)
 box(.69,.78,.035,.33,.38,.65,sage,warehouse,.008)
 for(let y=.08;y<.78;y+=.11)box(.65,.014,.02,.33,y,.676,windowMat,warehouse,.003)
 box(.76,.08,.33,.33,.85,.76,cream,warehouse,.015)
 mesh(new THREE.PlaneGeometry(.64,.32),gkSign,[-.55,.82,.646],warehouse)
 box(.32,.28,.018,-.55,.3,.648,windowMat,warehouse,.003)
 box(2.18,.08,.4,0,.015,.84,material('#c4d1bd'),warehouse,.02)
 function parcel(x,y,z,size=.48,parent=world){
  const group=new THREE.Group();group.position.set(x,y,z);parent.add(group)
  box(size,size,size,0,size/2,0,tan,group,.025)
  box(size*.16,.012,size*.99,0,size+.008,0,tape,group,.003)
  box(size*.16,size*.98,.012,0,size/2,size/2+.008,tape,group,.003)
  mesh(new THREE.PlaneGeometry(size*.55,size*.24),boxSign,[size*.12,size*.55,size/2+.018],group)
  return group
 }
 parcel(-.89,.065,.47,.42);parcel(-1.18,.065,.84,.36);parcel(-.94,.49,.47,.3)
 // Pickup house and delivery pedestal are symbols, not geographic locations.
 const home=new THREE.Group();home.position.set(-2.1,0,-.45);world.add(home)
 box(.73,.72,.68,0,.35,0,cream,home)
 const roof=mesh(new THREE.ConeGeometry(.59,.42,4),green,[0,.91,0],home);roof.rotation.y=Math.PI/4
 box(.19,.4,.025,.13,.2,.35,pine,home,.003);box(.18,.19,.025,-.19,.42,.35,windowMat,home,.003)
 box(.92,.08,.91,0,.015,0,sage,home)
 box(.95,.12,.95,2.02,.025,.05,cream)
 const delivery=parcel(2.02,.09,.05,.56)
 function pin(x,z){mesh(new THREE.CylinderGeometry(.018,.018,.43,8),rust,[x,.28,z]);mesh(new THREE.SphereGeometry(.085,16,12),rust,[x,.54,z]);const ring=mesh(new THREE.TorusGeometry(.14,.018,8,28),rust,[x,.065,z]);ring.rotation.x=Math.PI/2}
 pin(-2.6,.04);pin(2.65,.18)
 function tree(x,z){mesh(new THREE.CylinderGeometry(.04,.06,.42,8),tan,[x,.21,z]);mesh(new THREE.SphereGeometry(.24,12,10),green,[x,.62,z]);mesh(new THREE.SphereGeometry(.19,12,10),pine,[x+.1,.8,z+.02])}
 tree(-2.72,-1.55);tree(2.7,-1.55)
 // A small branded van follows the illustrative loop.
 const van=new THREE.Group();world.add(van)
 box(.63,.12,.99,0,.2,0,dark,van,.025)
 box(.62,.52,.65,0,.52,-.14,cream,van,.04)
 box(.6,.39,.38,0,.45,.36,green,van,.05)
 box(.46,.16,.018,0,.53,.56,windowMat,van,.008)
 mesh(new THREE.PlaneGeometry(.29,.16),gkSign,[0,.53,.194],van).rotation.y=Math.PI
 const sideSign=mesh(new THREE.PlaneGeometry(.36,.19),gkSign,[.32,.52,-.14],van);sideSign.rotation.y=Math.PI/2
 for(const x of [-.32,.32])for(const z of [-.32,.32]){const wheel=mesh(new THREE.CylinderGeometry(.125,.125,.09,16),dark,[x,.15,z],van);wheel.rotation.z=Math.PI/2}
 for(const x of [-.19,.19])box(.1,.07,.012,x,.33,.563,cream,van,.005)
 const shadow=mesh(new THREE.PlaneGeometry(7.7,5.5),new THREE.ShadowMaterial({opacity:.1}),[0,-.32,0]);materials.add(shadow.material);shadow.rotation.x=-Math.PI/2;shadow.castShadow=false
 let disposed=false,active=false,frame=0,last=0,elapsed=3
 const target={x:0,y:0},point=new THREE.Vector3(),tangent=new THREE.Vector3()
 function draw(){
  world.rotation.y+=(target.x*.1-world.rotation.y)*.06
  world.rotation.x+=(target.y*.025-world.rotation.x)*.06
  route.getPointAt((elapsed/32)%1,point);route.getTangentAt((elapsed/32)%1,tangent)
  van.position.copy(point);van.rotation.y=Math.atan2(tangent.x,tangent.z)
  delivery.position.y=.09+Math.sin(elapsed*1.4)*.025
  renderer.render(scene,camera)
 }
 function tick(now){if(disposed||!active)return;frame=requestAnimationFrame(tick);if(now-last<1000/30)return;const delta=last?Math.min((now-last)/1000,.08):0;last=now;elapsed+=delta;draw()}
 function setActive(value){if(disposed||value===active)return;active=value;last=0;if(active)frame=requestAnimationFrame(tick);else{cancelAnimationFrame(frame);draw()}}
 function resize(){if(disposed)return;const width=host.clientWidth,height=host.clientHeight;if(!width||!height)return;const half=2.85,aspect=width/height;camera.left=-half*aspect;camera.right=half*aspect;camera.top=half;camera.bottom=-half;camera.updateProjectionMatrix();renderer.setSize(width,height,false);draw()}
 function pointer(e){const rect=host.getBoundingClientRect();target.x=(e.clientX-rect.left)/rect.width*2-1;target.y=(e.clientY-rect.top)/rect.height*2-1}
 function leave(){target.x=0;target.y=0}
 function lost(e){e.preventDefault();onUnavailable()}
 const observer=new ResizeObserver(resize);observer.observe(host)
 host.addEventListener('pointermove',pointer);host.addEventListener('pointerleave',leave);canvas.addEventListener('webglcontextlost',lost)
 cleanup=()=>{if(disposed)return;disposed=true;active=false;cancelAnimationFrame(frame);observer.disconnect();host.removeEventListener('pointermove',pointer);host.removeEventListener('pointerleave',leave);canvas.removeEventListener('webglcontextlost',lost);release()}
 resize();draw()
 return {setActive,dispose:cleanup}
 } catch {cleanup();return null}
}
