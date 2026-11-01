import numpy as np, subprocess, os, sys
from scipy.signal import butter, sosfilt, fftconvolve
from scipy.io import wavfile
SR=44100; BPM=140
BAR=60/BPM*4; STEP=BAR/16
rng=np.random.default_rng(7)
WD=sys.argv[1]; OUT=sys.argv[2]

# ---- arrangement (bars) ----
SECTIONS=[("intro",4),("drop",8),("verse",8),("drop",8),("break",4),("drop",8),("outro",4)]
bars=[s for s,n in SECTIONS for _ in range(n)]
NB=len(bars); N=int((NB*BAR+3)*SR)
def buf(): return np.zeros(N)
def add(b,x,t,g=1.0):
    i=int(t*SR); 
    if i>=N: return
    x=x[:N-i]; b[i:i+len(x)]+=x*g
def filt(x,kind,f,order=2):
    sos=butter(order,f,btype=kind,fs=SR,output='sos'); return sosfilt(sos,x)
tt=lambda d: np.arange(int(d*SR))/SR

# ---- instruments ----
def kick():
    t=tt(.45); f=45+110*np.exp(-t*28); ph=2*np.pi*np.cumsum(f)/SR
    x=np.sin(ph)*np.exp(-t*7); x+=0.4*np.exp(-t*400)*rng.standard_normal(len(t))
    return np.tanh(x*2.2)
def snare():
    t=tt(.4); n=filt(rng.standard_normal(len(t)),'highpass',1200)*np.exp(-t*14)
    tone=np.sin(2*np.pi*190*t)*np.exp(-t*25)
    return np.tanh((n*1.2+tone*.7)*1.5)
def clap():
    t=tt(.35); n=filt(rng.standard_normal(len(t)),'bandpass',[900,5000])
    env=sum(np.exp(-np.clip(t-o,0,None)*60)*(t>=o) for o in (0,.012,.025))*.5+np.exp(-t*14)*.6
    return n*env
def hat(open_=False):
    t=tt(.25 if open_ else .06); n=filt(rng.standard_normal(len(t)),'highpass',7000)
    return n*np.exp(-t*(14 if open_ else 70))
def cowbell(f):
    t=tt(.5); x=np.sign(np.sin(2*np.pi*f*t))+np.sign(np.sin(2*np.pi*f*1.504*t))
    x=filt(x,'bandpass',[f*.9,f*4]); return x*(0.55*np.exp(-t*9)+.45*np.exp(-t*45))
def bass808(f,dur,gl=None):
    t=tt(dur); fr=np.full(len(t),f)
    if gl: fr=f+(gl-f)*np.exp(-t*30)
    ph=2*np.pi*np.cumsum(fr)/SR
    env=np.minimum(1,t*400)*np.exp(-t*2.6)*np.minimum(1,(dur-t)*40)
    return np.tanh(np.sin(ph)*env*3.2)
def pad(freqs,dur):
    t=tt(dur); x=np.zeros(len(t))
    for f in freqs:
        for d in (-.007,0,.007):
            x+=2*((t*f*(1+d))%1)-1
    x=filt(x,'lowpass',1100)
    return x*np.minimum(1,t*3)*np.minimum(1,(dur-t)*3)/9

def midi(n): return 440*2**((n-69)/12)
ROOT=[43.65,34.65*1.0,38.89,32.70]          # F1 Db1 Eb1 C1
CHORD=[[53,56,60],[49,53,56],[51,55,58],[48,52,55]]  # Fm Db Eb C (midi)
COW=[[(0,523),(3,523),(6,554),(10,523),(12,415)],
     [(0,415),(3,415),(6,698),(10,554),(12,415)],
     [(0,622),(3,622),(6,466),(10,392),(12,466)],
     [(0,392),(3,523),(6,622),(10,587),(12,523)]]
B8=[(0,0,3,None),(3,0,2,None),(6,1.189,3,1.5),(10,0,3,1.5),(13,0,3,None)]

drums=buf(); bass=buf(); cow=buf(); pd=buf(); hats=buf()
K=kick(); S=snare(); C=clap(); H=hat(); HO=hat(True)
kicks=[]
for b,sec in enumerate(bars):
    t0=b*BAR; ch=b%4
    full=sec in("drop","outro") ; lite=sec=="verse"
    if sec in("intro","break","drop","verse","outro"):
        add(pd,pad([midi(n) for n in CHORD[ch]],BAR*1.02),t0,.8)
    if full or lite:
        ks=[0,6,10] if full else [0,10]
        for s in ks: add(drums,K,t0+s*STEP,.9); kicks.append(t0+s*STEP)
        for s in (4,12): add(drums,S,t0+s*STEP,.8 if full else .55); add(drums,C,t0+s*STEP,.5)
        for s in range(0,16,2): add(hats,H,t0+s*STEP,.32+.1*(s%4==0))
        if full:
            for s in (13,14,15): add(hats,H,t0+s*STEP,.3)
            if b%4==3:  # roll
                for k in range(8): add(hats,H,t0+(12+k*0.5)*STEP,.25+.04*k)
            add(hats,HO,t0+7*STEP,.3)
        for s,m,l,gl in B8:
            f=ROOT[ch]*(m if m else 1)
            add(bass,bass808(f,l*STEP*1.0,f*gl if gl else None),t0+s*STEP,.95)
    if sec=="break" and b%4>=2:
        for s in (0,3,6): add(bass,bass808(ROOT[ch],3*STEP),t0+s*STEP,.9)
    if full or lite or sec=="intro" and b>=2:
        for s,f in COW[ch]:
            add(cow,cowbell(f),t0+s*STEP,.5 if full else .3)
    if sec=="intro" and b==3:  # riser
        t=tt(BAR); n=filt(rng.standard_normal(len(t)),'highpass',2500)*(t/BAR)**2; add(hats,n,t0,.35)
    if sec=="break" and b%4==3:
        for k in range(16): add(drums,S,t0+k*STEP,.2+.04*k)

def duck(x):
    d=np.ones(N)
    for t in kicks:
        i=int(t*SR); L=int(.25*SR); 
        if i+L<N: d[i:i+L]*=1-.55*np.exp(-np.arange(L)/SR/.07)
    return x*d
pd=duck(pd)

# ---- vocals ----
LINES={"burn":"Burn it down, burn it down","drift":"Drift all night, no brakes","blood":"Phonk in my blood, fire in my soul",
"fast":"Faster, faster, we never slow","yeah":"Yeah","skrt":"Skrrt skrrt"}
def tts(k,txt,voice="en-us+m3",p=15,s=135):
    w=os.path.join(WD,k+".wav")
    subprocess.run(["espeak-ng","-v",voice,"-p",str(p),"-s",str(s),"-g","3","-w",w,txt],check=True)
    sr,x=wavfile.read(w); x=x.astype(float)/32768
    if sr!=SR: x=np.interp(np.arange(0,len(x),sr/SR),np.arange(len(x)),x)
    x=x/np.max(np.abs(x)); i=np.where(np.abs(x)>.02)[0]; return x[i[0]:i[-1]]
V={k:tts(k,v) for k,v in LINES.items()}
def fit(x,dur,rate_lim=(.7,1.6)):
    r=np.clip(len(x)/SR/dur,*rate_lim); idx=np.arange(0,len(x)-1,r); return np.interp(idx,np.arange(len(x)),x)
voc=buf(); voc2=buf()
seq=["burn","drift","blood","fast"]
for b,sec in enumerate(bars):
    t0=b*BAR; ch=b%4
    if sec in("intro",) and b>=2: add(voc,fit(V["burn"],BAR*.9),t0,.8) if b==3 else None
    if sec=="drop":
        add(voc,fit(V["burn" if ch%2==0 else "drift"],BAR*.82),t0+STEP*.5,1.0)
        if ch%2==1: add(voc2,fit(V["yeah"],.35),t0+BAR*.88,.8)
    if sec=="verse":
        add(voc,fit(V[seq[ch]],BAR*.92),t0,.95)
    if sec=="break":
        if b%4==0: add(voc,fit(V["blood"],BAR*1.9),t0,1.0)
        if b%4==2: add(voc,fit(V["fast"],BAR*1.9),t0,1.0)
        if b%4==3: add(voc2,fit(V["skrt"],.7),t0+BAR*.5,.9)
    if sec=="outro" and b%4==0: add(voc,fit(V["burn"],BAR*1.8),t0,.9)
    if sec=="outro" and b%4==2: add(voc2,fit(V["skrt"],.8),t0,.9)
def voxfx(x):
    x=filt(x,'highpass',150); y=np.tanh(x*3.5)*.6+x*.4
    y=filt(y,'bandpass',[200,9000],1) if False else filt(y,'lowpass',9000)
    # double octave-up shimmer layer
    up=np.zeros_like(y); idx=np.arange(0,len(y)-1,2.0); h=np.interp(idx,np.arange(len(y)),y); up[:len(h)]=h*.0
    # ping-pong echo
    d=int(STEP*3*SR); out=y.copy(); e=y.copy()
    for k in range(1,5):
        out[d*k:]+=y[:N-d*k]*(.45**k)
    # reverb
    L=int(2.2*SR); ir=rng.standard_normal(L)*np.exp(-np.arange(L)/SR*2.4); ir=filt(ir,'lowpass',5000)
    wet=fftconvolve(out,ir)[:N]; wet/=np.max(np.abs(wet))+1e-9
    return out+wet*.35*np.max(np.abs(out))
vox=voxfx(voc)*1.0+voxfx(voc2)*.8
vox=duck(vox*.0)+vox*.85  # keep vocal forward, tiny pump

# ---- mix ----
mix=drums*.9+hats*.7+bass*1.0+cow*.55+pd*.45+vox*.9
mix=np.tanh(mix*0.7)/np.tanh(0.7)
mix=mix[:int((NB*BAR+2.0)*SR)]
mix=mix/np.max(np.abs(mix))*.89
fade=int(1.5*SR); mix[-fade:]*=np.linspace(1,0,fade)
wavfile.write(os.path.join(WD,"mix.wav"),SR,(mix*32767).astype(np.int16))
subprocess.run(["ffmpeg","-y","-loglevel","error","-i",os.path.join(WD,"mix.wav"),"-codec:a","libmp3lame","-b:a","320k",
 "-metadata","title=Burning Phonk","-metadata","artist=Claude",OUT],check=True)
print("dur",len(mix)/SR)
