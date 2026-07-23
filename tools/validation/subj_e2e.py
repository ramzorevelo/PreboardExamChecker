import cv2, numpy as np, glob, os
# End-to-end: gap-edge localization on the top-anchored rectified strips, draw read rects.
PRIORS=[0.354,0.55,0.76]; HALFBUB=0.0065; SIG=0.015; WIN=0.075
files=sorted(glob.glob(r"C:\tmp\cal\subj_topmed\*.png"))
def runs(mask):
    n=len(mask);o=[];x=0
    while x<n:
        if mask[x]:
            s=x
            while x<n and mask[x]:x+=1
            o.append((s,x-1))
        else:x+=1
    return o
rows=[]
for f in files:
    img=cv2.imread(f,cv2.IMREAD_GRAYSCALE);h,w=img.shape
    # subject binary like computeSubjectBinary: CLAHE + Otsu + open2
    cl=cv2.createCLAHE(2.0,(8,8)); e=cl.apply(img)
    _,b=cv2.threshold(e,0,255,cv2.THRESH_BINARY_INV|cv2.THRESH_OTSU)
    b=cv2.morphologyEx(b,cv2.MORPH_OPEN,cv2.getStructuringElement(cv2.MORPH_RECT,(2,2)))
    lo,hi=int(0.30*h),int(0.70*h);bandH=hi-lo
    cd=(b[lo:hi,:]>0).sum(0).astype(float)
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
    cellW=int(0.0125*w);readW=int(cellW*1.5)
    # shared row y: detect band per bubble center; use 0.48h default
    cys=[]
    cxs=[]
    for p in PRIORS:
        t=p*w+HALFBUB*w
        near=[ee for ee in edges if abs(ee-t)<=WIN*w]
        cx=(min(near,key=lambda ee:abs(ee-t))-HALFBUB*w) if near else p*w
        cxs.append(cx)
    for cx in cxs:
        col=int(round(cx))
        # band y
        rx0=max(0,col-cellW//2);rx1=min(w,col+cellW//2)
        rowmask=[ (b[y,rx0:rx1]>0).sum()>=2 for y in range(int(0.20*h),int(0.80*h))]
        cys.append(None)
    # shared y = 0.48h
    cy=int(0.48*h);readH=int(0.34*h*1.3)
    for cx in cxs:
        col=int(round(cx))
        cv2.rectangle(vis,(col-readW//2,cy-readH//2),(col+readW//2,cy+readH//2),(0,0,255),2)
    small=cv2.resize(vis,(1200,63))
    cv2.putText(small,os.path.basename(f).replace('.png',''),(2,12),cv2.FONT_HERSHEY_SIMPLEX,0.4,(0,255,0),1)
    rows.append(small)
for ci in range(0,len(rows),13):
    cv2.imwrite(r'C:\tmp\cal\e2e_%d.png'%(ci//13),np.vstack(rows[ci:ci+13]))
print("rows",len(rows))
