import cv2, numpy as np, glob, os
OUT=r"C:\tmp\cal\newsub"; os.makedirs(OUT,exist_ok=True)
bins=sorted(glob.glob(r"C:\tmp\newSub\answer_warped_binary_*.png"))
grays=sorted([g for g in glob.glob(r"C:\tmp\newSub\answer_warped_*.png")
              if "binary" not in g and "boxes" not in g])
SUBJW,SUBJH=2400,126
PRIORS=[0.354,0.55,0.76];HALFBUB=0.0065;SIG=0.015;WIN=0.075

def find_c(b):
    H,W=b.shape;band=b[0:int(0.15*H),:]
    _,m=cv2.threshold(band,127,255,cv2.THRESH_BINARY_INV)
    m=cv2.morphologyEx(m,cv2.MORPH_CLOSE,cv2.getStructuringElement(cv2.MORPH_RECT,(15,1)))
    cs,_=cv2.findContours(m,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    best=None;ba=0
    for c in cs:
        x,y,w,h=cv2.boundingRect(c)
        if w>=0.80*W and 0.030*H<=h<=0.150*H and y<=0.10*H and w*h>ba:ba=w*h;best=c
    return best

def smooth_edge(raw):
    n=len(raw);valid=np.where(raw>=0)[0]
    if len(valid)==0:return np.zeros(n)
    filled=np.interp(np.arange(n),valid,raw[valid])
    med=np.array([np.median(filled[max(0,i-2):i+3]) for i in range(n)])
    half=20;return np.array([med[max(0,i-half):i+half+1].mean() for i in range(n)])

def rectify(g,c):
    H,W=g.shape;x,y,w,h=cv2.boundingRect(c);pad=8
    bx0=max(0,x-pad);bx1=min(W,x+w+pad);by0=max(0,y-pad);by1=min(H,y+h+pad)
    cw=bx1-bx0;m=np.zeros((by1-by0,cw),np.uint8);cv2.drawContours(m,[c],-1,255,-1,offset=(-bx0,-by0))
    top=np.full(cw,-1.0);bot=np.full(cw,-1.0)
    for cx in range(cw):
        col=np.where(m[:,cx]>0)[0]
        if len(col):top[cx]=col[0];bot[cx]=col[-1]
    if (top>=0).sum()<0.6*cw:return None
    st=smooth_edge(top);hh=(bot-top)[(bot>0)&(top>=0)];boxH=np.median(hh)
    xo=np.linspace(0,cw-1,SUBJW);to=np.interp(xo,np.arange(cw),st)
    mx=np.zeros((SUBJH,SUBJW),np.float32);my=np.zeros((SUBJH,SUBJW),np.float32)
    for u in range(SUBJW):
        mx[:,u]=bx0+xo[u]
        my[:,u]=by0+to[u]+np.linspace(0,1,SUBJH)*boxH
    return cv2.remap(g,mx,my,cv2.INTER_LINEAR,borderValue=255)

def runs(m):
    n=len(m);o=[];x=0
    while x<n:
        if m[x]:
            s=x
            while x<n and m[x]:x+=1
            o.append((s,x-1))
        else:x+=1
    return o

rows=[];stats=[]
for bf,gf in zip(bins,grays):
    b=cv2.imread(bf,0);g=cv2.imread(gf,0)
    hh=os.path.basename(bf).split('_')[-2]
    c=find_c(b)
    if c is None: continue
    img=rectify(g,c)
    if img is None: continue
    h,w=img.shape
    cl=cv2.createCLAHE(2.0,(8,8));e=cl.apply(img)
    _,bb=cv2.threshold(e,0,255,cv2.THRESH_BINARY_INV|cv2.THRESH_OTSU)
    bb=cv2.morphologyEx(bb,cv2.MORPH_OPEN,cv2.getStructuringElement(cv2.MORPH_RECT,(2,2)))
    lo,hi=int(0.30*h),int(0.70*h);bandH=hi-lo
    cd=(bb[lo:hi,:]>0).sum(0).astype(float)
    rr=[r for r in runs(cd>0.12*bandH) if r[1]-r[0]>=2]
    pent=rr[0][1] if rr else 0
    for i in range(1,len(rr)):
        if rr[i][0]-pent<=int(0.03*w):pent=rr[i][1]
        else:break
    cont=[r for r in rr if r[0]>pent]
    edges=[]
    for i in range(1,len(cont)):
        if cont[i][0]-cont[i-1][1]>=SIG*w:edges.append(cont[i-1][1])
    if cont and (w-cont[-1][1])>=SIG*w:edges.append(cont[-1][1])
    vis=cv2.cvtColor(img,cv2.COLOR_GRAY2BGR)
    readW=int(0.0125*w*1.5);readH=int(0.34*h*1.3);cy=int(0.48*h)
    nloc=0
    for p in PRIORS:
        t=p*w+HALFBUB*w;near=[ee for ee in edges if abs(ee-t)<=WIN*w]
        if near:cx=int(min(near,key=lambda ee:abs(ee-t))-HALFBUB*w);col=(0,0,255);nloc+=1
        else:cx=int(p*w);col=(0,165,255)
        cv2.rectangle(vis,(cx-readW//2,cy-readH//2),(cx+readW//2,cy+readH//2),col,2)
    small=cv2.resize(vis,(1200,63))
    cv2.putText(small,'%s e%d L%d'%(hh,len(edges),nloc),(2,12),cv2.FONT_HERSHEY_SIMPLEX,0.4,(0,255,0),1)
    rows.append(small);stats.append((hh,len(edges),nloc,[round(e/w,3) for e in edges]))
for ci in range(0,len(rows),12):
    cv2.imwrite(r'C:\tmp\cal\ns_%d.png'%(ci//12),np.vstack(rows[ci:ci+12]))
for s in stats: print(s)
