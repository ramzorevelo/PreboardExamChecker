import cv2, numpy as np, glob, os

OUT=r"C:\tmp\cal\subj_topmed"; os.makedirs(OUT,exist_ok=True)
bins=sorted(glob.glob(r"C:\tmp\subNewRect\answer_warped_binary_*.png"))
grays=sorted([g for g in glob.glob(r"C:\tmp\subNewRect\answer_warped_*.png")
              if "binary" not in g and "boxes" not in g])
SUBJW,SUBJH=2400,126

def find_subj_contour(binary):
    H,W=binary.shape
    band=binary[0:int(0.15*H),:]
    _,mask=cv2.threshold(band,127,255,cv2.THRESH_BINARY_INV)
    mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,cv2.getStructuringElement(cv2.MORPH_RECT,(15,1)))
    cnts,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    best=None;ba=0
    for c in cnts:
        x,y,ww,hh=cv2.boundingRect(c)
        if ww>=0.80*W and 0.030*H<=hh<=0.150*H and y<=0.10*H and ww*hh>ba:ba=ww*hh;best=c
    return best

def fit_robust(xs,ys,order=2):
    xs=np.asarray(xs,float);ys=np.asarray(ys,float);keep=np.ones(len(xs),bool)
    c=np.polyfit(xs,ys,order)
    for _ in range(4):
        res=np.abs(np.polyval(c,xs)-ys);s=np.median(res[keep])*1.4826+1e-6
        nk=res<3*s
        if nk.sum()<len(xs)*0.4:break
        keep=nk;c=np.polyfit(xs[keep],ys[keep],order)
    return c

def rectify(gray, contour, outW, outH):
    H,W=gray.shape
    x,y,ww,hh=cv2.boundingRect(contour)
    pad=8; bx0=max(0,x-pad); bx1=min(W,x+ww+pad); by0=max(0,y-pad); by1=min(H,y+hh+pad)
    cw=bx1-bx0; ch=by1-by0
    mask=np.zeros((ch,cw),np.uint8)
    cv2.drawContours(mask,[contour],-1,255,-1,offset=(-bx0,-by0))
    topRaw=np.full(cw,-1.0); botRaw=np.full(cw,-1.0)
    for cx in range(cw):
        col=np.where(mask[:,cx]>0)[0]
        if len(col)>0: topRaw[cx]=col[0]; botRaw[cx]=col[-1]
    cols=np.where(topRaw>=0)[0]
    if len(cols)<0.6*cw: return None
    ctop=fit_robust(cols, topRaw[cols])
    heights=(botRaw[cols]-topRaw[cols]); heights=heights[heights>0]
    boxH=np.median(heights)
    xs_out=np.linspace(0,cw-1,outW)
    top_out=np.polyval(ctop,xs_out)
    mapX=np.zeros((outH,outW),np.float32); mapY=np.zeros((outH,outW),np.float32)
    for u in range(outW):
        srcX=bx0+xs_out[u]; t=top_out[u]
        for v in range(outH):
            fy=v/(outH-1) if outH>1 else 0
            mapX[v,u]=srcX; mapY[v,u]=by0+t+fy*boxH
    return cv2.remap(gray,mapX,mapY,cv2.INTER_LINEAR,borderValue=255)

rows=[]
for bf,gf in zip(bins,grays):
    b=cv2.imread(bf,cv2.IMREAD_GRAYSCALE); g=cv2.imread(gf,cv2.IMREAD_GRAYSCALE)
    hh=os.path.basename(bf).replace(".png","").split("_")[-2]
    c=find_subj_contour(b)
    if c is None: continue
    out=rectify(g,c,SUBJW,SUBJH)
    if out is None: continue
    cv2.imwrite(os.path.join(OUT,hh+".png"),out)
    small=cv2.resize(out,(1200,63))
    lbl=cv2.cvtColor(small,cv2.COLOR_GRAY2BGR)
    cv2.putText(lbl,hh,(2,12),cv2.FONT_HERSHEY_SIMPLEX,0.4,(0,0,255),1)
    rows.append(lbl)
# montage in chunks of 13
for ci in range(0,len(rows),13):
    chunk=rows[ci:ci+13]
    mont=np.vstack(chunk)
    cv2.imwrite(r'C:\tmp\cal\mont_%d.png'%(ci//13),mont)
print("rows",len(rows))
