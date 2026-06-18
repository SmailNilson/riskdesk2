import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Self-contained MNQ edge search.
 * Signals computed on aggregated TF; SL/TP resolved intrabar on real 1m candles.
 * Reports per-month WR for robustness (target: >70% WR on >=70% of months).
 * No project deps: javac MnqEdgeSearch.java && java MnqEdgeSearch
 */
public class MnqEdgeSearch {
    static final double PV = 2.0;            // MNQ $ per point
    static final ZoneId NY = ZoneId.of("America/New_York");

    // ---- raw 1m arrays ----
    static int N;
    static long[] ts;            // epoch seconds
    static double[] o,h,l,c; static long[] vol; static String[] cm;
    static int[] etY,etMo,etD,etH,etMin; static long[] etDayKey; // yyyymmdd

    public static void main(String[] args) throws Exception {
        load("/tmp/mnq_1m.csv");
        System.out.println("Loaded "+N+" 1m bars  "+isoNY(ts[0])+" -> "+isoNY(ts[N-1]));

        int[] tfs = {10,15};
        if (args.length>0 && args[0].startsWith("tf=")) {
            String[] p = args[0].substring(3).split(",");
            tfs = new int[p.length]; for(int i=0;i<p.length;i++) tfs[i]=Integer.parseInt(p[i]);
        }

        // tp variants: {mode, atr}
        double[][] tpVars = {{0,0.75},{0,1.0},{1,0},{2,0}}; // ATR0.75, ATR1.0, VWAP, BBmid
        int[][] rsiPairs = {{35,65},{30,70}};
        double[] sls = {2.0,2.5,3.0};
        int[] minConf = {3,4};

        List<Res> all = new ArrayList<>();
        for (int tf : tfs) {
            TF t = aggregate(tf); Ind in = indicators(t);
            for (int[] rp : rsiPairs)
            for (double[] tv : tpVars)
            for (int mc : minConf)
            for (boolean gate : new boolean[]{false,true})
            for (boolean confirm : new boolean[]{false,true})
            for (boolean str : new boolean[]{false,true})
            for (double slOrMax : sls) {
                Cfg cfg = new Cfg();
                cfg.tf=tf; cfg.rsiBuy=rp[0]; cfg.rsiSell=rp[1];
                cfg.tpMode=(int)tv[0]; cfg.tpAtr=tv[1]; cfg.minConf=mc;
                cfg.rthOnly=true; cfg.maxHoldMin=tf*30; cfg.family="A";
                cfg.costPts=1.0; cfg.entryNextOpen=true;
                cfg.hardTrendGate=gate; cfg.confirmEntry=confirm;
                if(str){ cfg.structStop=true; cfg.maxSlAtr=slOrMax; cfg.minSlAtr=0.8; cfg.structBufAtr=0.3; cfg.structLook=5; }
                else   { cfg.slAtr=slOrMax; }
                Res r = run(t,in,cfg);
                if (r.trades>=40) all.add(r);
            }
        }
        report(all);

        // ---- finalist deep-dive at cost 1.0 and 2.0 pts ----
        System.out.println("\n========== FINALIST DEEP-DIVE (cost sensitivity) ==========");
        Map<Integer,TF> tcache=new HashMap<>(); Map<Integer,Ind> icache=new HashMap<>();
        for(int tf: new int[]{10,15}){ TF t=aggregate(tf); tcache.put(tf,t); icache.put(tf,indicators(t)); }
        Cfg[] fin = new Cfg[3];
        fin[0]=mk(15,35,65,0,0.75,3.0,3,false); // best PF/expectancy
        fin[1]=mk(10,30,70,0,0.75,3.0,3,true);  // best drawdown + 6/6
        fin[2]=mk(15,35,65,0,0.75,3.0,4,false); // highest WR
        for(Cfg cf: fin){ detail(tcache.get(cf.tf),icache.get(cf.tf),cf); }

        System.out.println("\n========== RANGE-ONLY champions (full period) ==========");
        Cfg rc1=mk(15,35,65,0,0.75,3.0,3,false); rc1.rangeOnly=true; detail(tcache.get(15),icache.get(15),rc1);
        Cfg rc2=mk(10,35,65,0,0.75,2.5,3,false); rc2.rangeOnly=true; detail(tcache.get(10),icache.get(10),rc2);
        Cfg rc3=mk(15,35,65,0,0.75,3.0,3,true);  rc3.rangeOnly=true; detail(tcache.get(15),icache.get(15),rc3);

        walkForward(tcache,icache);
    }

    // Anchored walk-forward: choose best cfg on months < M (train), score on month M (unseen).
    static void walkForward(Map<Integer,TF> tc,Map<Integer,Ind> ic){
        System.out.println("\n========== WALK-FORWARD (re-optimize per window, score next month) ==========");
        List<Res> grid=new ArrayList<>();
        boolean GATE_ONLY = !"all".equals(System.getProperty("wf",""));
        boolean RANGE = "1".equals(System.getProperty("range",""));
        for(int tf: new int[]{10,15}) for(int[] rp: new int[][]{{35,65},{30,70}})
        for(double sl: new double[]{2.5,3.0}) for(int mc: new int[]{3,4})
        for(boolean g: (GATE_ONLY? new boolean[]{true} : new boolean[]{false,true})){
            Cfg c=mk(tf,rp[0],rp[1],0,0.75,sl,mc,g); c.costPts=1.0; c.rangeOnly=RANGE; Res r=run(tc.get(tf),ic.get(tf),c); grid.add(r);
        }
        System.out.println("(grid: "+(GATE_ONLY?"GATE-FORCED":"all")+(RANGE?" +RANGE-ONLY":"")+")");
        String[] M={"2026-01","2026-02","2026-03","2026-04","2026-05","2026-06"};
        System.out.printf("%-8s %-34s %14s %16s%n","test","chosen-on-train","train(WR/PF/trd)","TEST(WR/PF/trd)");
        int pass=0,tot=0;
        for(int tm=2;tm<=5;tm++){
            Res best=null; double bestWR=-1,btW=0,btP=0; int btT=0;
            for(Res r: grid){
                double w=0,t=0,gw=0,gl=0;
                for(int m=0;m<tm;m++){ double[] s=r.mStat.get(M[m]); if(s!=null){w+=s[0];t+=s[1];gw+=s[2];gl+=s[3];} }
                if(t<40) continue; double pf=gl>0?gw/gl:99; if(pf<1.05) continue; double wr=100*w/t;
                if(wr>bestWR){bestWR=wr;best=r;btW=wr;btP=pf;btT=(int)t;}
            }
            if(best==null){ System.out.printf("%-8s no eligible config%n",M[tm]); continue; }
            double[] s=best.mStat.get(M[tm]); double twr= s!=null&&s[1]>0?100*s[0]/s[1]:0;
            double tpf= s!=null&&s[3]>0?s[2]/s[3]:(s!=null&&s[2]>0?99:0); int ttr= s!=null?(int)s[1]:0;
            tot++; if(twr>=70) pass++;
            System.out.printf("%-8s %-34s  %4.1f/%4.2f/%-4d   %4.1f/%4.2f/%-4d %s%n",
                M[tm], best.cfg.toString(), btW,btP,btT, twr,tpf,ttr, (twr>=70?"PASS":"<70"));
        }
        System.out.println("Walk-forward months with test-WR>=70%: "+pass+"/"+tot);
    }
    static Cfg mk(int tf,int rb,int rs,int tpMode,double tpAtr,double sl,int conf,boolean gate){
        Cfg c=new Cfg(); c.tf=tf;c.rsiBuy=rb;c.rsiSell=rs;c.tpMode=tpMode;c.tpAtr=tpAtr;c.slAtr=sl;c.minConf=conf;
        c.rthOnly=true;c.maxHoldMin=tf*30;c.family="A";c.entryNextOpen=true;c.hardTrendGate=gate;c.beTrigAtr=1.0;return c;
    }
    static void detail(TF t,Ind in,Cfg base){
        System.out.println("\n--- "+base+" ---");
        for(double cost: new double[]{1.0,2.0}){
            base.costPts=cost; Res r=run(t,in,base);
            StringBuilder mb=new StringBuilder();
            for(var e:r.month.entrySet()){int[] v=e.getValue();mb.append(e.getKey().substring(5)).append(":").append(v[1]>0?Math.round(100.0*v[0]/v[1]):0).append("%(").append(v[1]).append(") ");}
            System.out.printf("cost=%.1fpt | trd=%d WR=%.1f%% PF=%.2f exp=%.2fpt avgW=%.1f avgL=%.1f maxLoss=%.0f maxDD=%.0fpt($%.0f) | trnWR=%.1f tstWR=%.1f%n",
                cost, r.trades, r.wr(), r.pf(), r.expPts(), r.avgW(), r.avgL(), r.maxLoss, r.maxDD, r.maxDD*PV, r.splWr(0), r.splWr(1));
            System.out.println("   monthly: "+mb);
        }
    }

    // ===================== DATA =====================
    static void load(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path));
        N=lines.size();
        ts=new long[N]; o=new double[N]; h=new double[N]; l=new double[N]; c=new double[N];
        vol=new long[N]; cm=new String[N];
        etY=new int[N];etMo=new int[N];etD=new int[N];etH=new int[N];etMin=new int[N];etDayKey=new long[N];
        for (int i=0;i<N;i++){
            String[] f=lines.get(i).split(",");
            ts[i]=Long.parseLong(f[0]);
            o[i]=Double.parseDouble(f[1]); h[i]=Double.parseDouble(f[2]);
            l[i]=Double.parseDouble(f[3]); c[i]=Double.parseDouble(f[4]);
            vol[i]=Long.parseLong(f[5]); cm[i]= f.length>6? f[6] : "";
            ZonedDateTime z=Instant.ofEpochSecond(ts[i]).atZone(NY);
            etY[i]=z.getYear();etMo[i]=z.getMonthValue();etD[i]=z.getDayOfMonth();
            etH[i]=z.getHour();etMin[i]=z.getMinute();
            etDayKey[i]=etY[i]*10000L+etMo[i]*100L+etD[i];
        }
    }
    static String isoNY(long s){ return Instant.ofEpochSecond(s).atZone(NY).toLocalDateTime().toString(); }
    static String monthKey(int i){ return String.format("%04d-%02d",etY[i],etMo[i]); }

    // ===================== AGGREGATION =====================
    static class TF {
        int tfMin, n;
        double[] o,h,l,c; long[] v; long[] tsEnd; // per TF bar
        int[] startIdx,endIdx;     // 1m index range (inclusive)
        boolean[] rollFlag;        // contract change within bar
        int[] sessId;              // ET-day session id (for VWAP/CVD reset)
        int[] mIdx;                // index of representative 1m bar (endIdx) for ET fields
    }
    static TF aggregate(int tfMin){
        int tfSec=tfMin*60;
        ArrayList<Integer> starts=new ArrayList<>(), ends=new ArrayList<>();
        long curBucket=Long.MIN_VALUE; int s=0;
        for(int i=0;i<N;i++){
            long b=ts[i]/tfSec;
            if(b!=curBucket){ if(i>0){starts.add(s);ends.add(i-1);} curBucket=b; s=i; }
        }
        starts.add(s); ends.add(N-1);
        int n=starts.size();
        TF t=new TF(); t.tfMin=tfMin; t.n=n;
        t.o=new double[n];t.h=new double[n];t.l=new double[n];t.c=new double[n];t.v=new long[n];
        t.tsEnd=new long[n];t.startIdx=new int[n];t.endIdx=new int[n];t.rollFlag=new boolean[n];
        t.sessId=new int[n]; t.mIdx=new int[n];
        long lastDay=-1; int sess=-1;
        for(int k=0;k<n;k++){
            int a=starts.get(k),z=ends.get(k);
            t.startIdx[k]=a;t.endIdx[k]=z;t.mIdx[k]=z;
            double hi=h[a],lo=l[a]; long vv=0; boolean roll=false;
            for(int i=a;i<=z;i++){ hi=Math.max(hi,h[i]); lo=Math.min(lo,l[i]); vv+=vol[i]; if(!cm[i].equals(cm[a]))roll=true; }
            t.o[k]=o[a];t.h[k]=hi;t.l[k]=lo;t.c[k]=c[z];t.v[k]=vv;t.tsEnd[k]=ts[z];t.rollFlag[k]=roll;
            if(etDayKey[z]!=lastDay){ sess++; lastDay=etDayKey[z]; }
            t.sessId[k]=sess;
        }
        return t;
    }

    // ===================== INDICATORS =====================
    static class Ind {
        double[] close;
        double[] ema9,ema20,ema50,ema200,sma20,kama;
        double[] rsi, atr;
        double[] bbMid,bbUp,bbLo,bbPctB,bbWidth;
        double[] vwap,vwUp,vwLo;
        double[] wt1,wt2; boolean[] wtCrossUp,wtCrossDn;
        double[] stK,stD; boolean[] stCrossUp,stCrossDn;
        double[] cmf, chOsc;
        double[] stDir;            // supertrend dir +1/-1
        double[] macd,macdSig,macdHist;
        double[] cvd, deltaOsc;
        int[] smcBias;             // +1/-1 from swing BOS
        double[] pdPct;            // 0..1 within dealing range
        int[] regime;              // +2 trendUp,+1 weakUp,0 range,-1 weakDn,-2 trendDn
    }

    static Ind indicators(TF t){
        int n=t.n; Ind x=new Ind();
        double[] cc=t.c, hh=t.h, ll=t.l; long[] vv=t.v; x.close=cc;
        x.ema9=ema(cc,9); x.ema20=ema(cc,20); x.ema50=ema(cc,50); x.ema200=ema(cc,200);
        x.sma20=sma(cc,20); x.kama=kama(cc,10,2,30);
        x.rsi=rsi(cc,14); x.atr=atr(hh,ll,cc,14);
        // Bollinger 20,2
        x.bbMid=x.sma20; x.bbUp=new double[n];x.bbLo=new double[n];x.bbPctB=new double[n];x.bbWidth=new double[n];
        for(int i=0;i<n;i++){ if(i<19){x.bbUp[i]=x.bbLo[i]=cc[i];x.bbPctB[i]=0.5;continue;}
            double m=x.sma20[i]; double s2=0; for(int j=i-19;j<=i;j++){double d=cc[j]-m;s2+=d*d;} double sd=Math.sqrt(s2/20);
            x.bbUp[i]=m+2*sd;x.bbLo[i]=m-2*sd;x.bbWidth[i]=m!=0?(x.bbUp[i]-x.bbLo[i])/m:0;
            double den=x.bbUp[i]-x.bbLo[i]; x.bbPctB[i]= den>0?(cc[i]-x.bbLo[i])/den:0.5; }
        // VWAP session (reset ET midnight) + sigma bands
        x.vwap=new double[n];x.vwUp=new double[n];x.vwLo=new double[n];
        { int sess=-1; double cumPV=0,cumV=0,cumP2V=0;
          for(int i=0;i<n;i++){ if(t.sessId[i]!=sess){sess=t.sessId[i];cumPV=0;cumV=0;cumP2V=0;}
            double tp=(hh[i]+ll[i]+cc[i])/3.0; double v=vv[i]<=0?1:vv[i];
            cumPV+=tp*v;cumV+=v;cumP2V+=tp*tp*v; double vw=cumPV/cumV; x.vwap[i]=vw;
            double var=Math.max(0,cumP2V/cumV-vw*vw); double sd=Math.sqrt(var);
            x.vwUp[i]=vw+2*sd;x.vwLo[i]=vw-2*sd; } }
        // WaveTrend (LazyBear) n1=10 n2=21
        { double[] ap=new double[n]; for(int i=0;i<n;i++)ap[i]=(hh[i]+ll[i]+cc[i])/3.0;
          double[] esa=ema(ap,10); double[] de=new double[n]; for(int i=0;i<n;i++)de[i]=Math.abs(ap[i]-esa[i]);
          double[] d=ema(de,10); double[] ci=new double[n];
          for(int i=0;i<n;i++){ double dd=0.015*d[i]; ci[i]= dd!=0?(ap[i]-esa[i])/dd:0; }
          x.wt1=ema(ci,21); x.wt2=sma(x.wt1,4); }
        x.wtCrossUp=new boolean[n];x.wtCrossDn=new boolean[n];
        for(int i=1;i<n;i++){ x.wtCrossUp[i]=x.wt1[i-1]<=x.wt2[i-1]&&x.wt1[i]>x.wt2[i];
            x.wtCrossDn[i]=x.wt1[i-1]>=x.wt2[i-1]&&x.wt1[i]<x.wt2[i]; }
        // Stochastic 14,3,3
        { double[] rawK=new double[n];
          for(int i=0;i<n;i++){ int a=Math.max(0,i-13); double hi=hh[a],lo=ll[a];
            for(int j=a;j<=i;j++){hi=Math.max(hi,hh[j]);lo=Math.min(lo,ll[j]);}
            double den=hi-lo; rawK[i]= den>0?100*(cc[i]-lo)/den:50; }
          x.stK=sma(rawK,3); x.stD=sma(x.stK,3); }
        x.stCrossUp=new boolean[n];x.stCrossDn=new boolean[n];
        for(int i=1;i<n;i++){ x.stCrossUp[i]=x.stK[i-1]<=x.stD[i-1]&&x.stK[i]>x.stD[i];
            x.stCrossDn[i]=x.stK[i-1]>=x.stD[i-1]&&x.stK[i]<x.stD[i]; }
        // CMF 20 + Chaikin osc
        { double[] mfv=new double[n]; double[] ad=new double[n]; double cum=0;
          for(int i=0;i<n;i++){ double rng=hh[i]-ll[i]; double mult= rng>0?((cc[i]-ll[i])-(hh[i]-cc[i]))/rng:0;
            mfv[i]=mult*vv[i]; cum+=mfv[i]; ad[i]=cum; }
          x.cmf=new double[n];
          for(int i=0;i<n;i++){ int a=Math.max(0,i-19); double sm=0,sv=0; for(int j=a;j<=i;j++){sm+=mfv[j];sv+=vv[j];} x.cmf[i]= sv>0?sm/sv:0; }
          double[] adF=ema(ad,3),adS=ema(ad,10); x.chOsc=new double[n]; for(int i=0;i<n;i++)x.chOsc[i]=adF[i]-adS[i]; }
        // Supertrend 10,3
        x.stDir=supertrendDir(hh,ll,cc,10,3.0);
        // MACD 12,26,9
        { double[] e12=ema(cc,12),e26=ema(cc,26); x.macd=new double[n]; for(int i=0;i<n;i++)x.macd[i]=e12[i]-e26[i];
          x.macdSig=ema(x.macd,9); x.macdHist=new double[n]; for(int i=0;i<n;i++)x.macdHist[i]=x.macd[i]-x.macdSig[i]; }
        // CVD (CLV delta) session reset + delta oscillator
        { double[] delta=new double[n]; x.cvd=new double[n]; int sess=-1; double cum=0;
          for(int i=0;i<n;i++){ double rng=hh[i]-ll[i]; double clv= rng>0?((cc[i]-ll[i])-(hh[i]-cc[i]))/rng:0; delta[i]=clv*vv[i];
            if(t.sessId[i]!=sess){sess=t.sessId[i];cum=0;} cum+=delta[i]; x.cvd[i]=cum; }
          double[] df=ema(delta,3),ds=ema(delta,10); x.deltaOsc=new double[n]; for(int i=0;i<n;i++)x.deltaOsc[i]=df[i]-ds[i]; }
        // SMC swing bias + PD zone (causal pivots, L=2)
        { int L=2; x.smcBias=new int[n]; x.pdPct=new double[n];
          double lastPH=Double.NaN,lastPL=Double.NaN; int bias=0;
          double rangeHi=Double.NaN,rangeLo=Double.NaN;
          for(int i=0;i<n;i++){
            // confirm pivot at i-L using window [i-2L, i]
            int p=i-L;
            if(p-L>=0){
              boolean ph=true,pl=true; double hv=hh[p],lv=ll[p];
              for(int j=p-L;j<=p+L;j++){ if(hh[j]>hv)ph=false; if(ll[j]<lv)pl=false; }
              if(ph){lastPH=hv;rangeHi=hv;} if(pl){lastPL=lv;rangeLo=lv;}
            }
            if(!Double.isNaN(lastPH)&&cc[i]>lastPH){bias=1;}
            if(!Double.isNaN(lastPL)&&cc[i]<lastPL){bias=-1;}
            x.smcBias[i]=bias;
            if(!Double.isNaN(rangeHi)&&!Double.isNaN(rangeLo)&&rangeHi>rangeLo)
                x.pdPct[i]=(cc[i]-rangeLo)/(rangeHi-rangeLo); else x.pdPct[i]=0.5;
          } }
        // Regime
        x.regime=new int[n];
        for(int i=0;i<n;i++){ if(i<200){x.regime[i]=0;continue;}
            boolean up=x.ema9[i]>x.ema50[i]&&x.ema50[i]>x.ema200[i];
            boolean dn=x.ema9[i]<x.ema50[i]&&x.ema50[i]<x.ema200[i];
            boolean wide=x.bbWidth[i]> avg(x.bbWidth,i,50);
            x.regime[i]= up?(wide?2:1): dn?(wide?-2:-1):0; }
        return x;
    }

    static double avg(double[] a,int i,int w){int s=Math.max(0,i-w+1);double sum=0;int c=0;for(int j=s;j<=i;j++){sum+=a[j];c++;}return c>0?sum/c:0;}
    static double[] ema(double[] a,int p){int n=a.length;double[] e=new double[n];double k=2.0/(p+1);double prev=a[0];for(int i=0;i<n;i++){prev= i==0?a[0]:a[i]*k+prev*(1-k);e[i]=prev;}return e;}
    static double[] sma(double[] a,int p){int n=a.length;double[] s=new double[n];double sum=0;for(int i=0;i<n;i++){sum+=a[i];if(i>=p)sum-=a[i-p];s[i]= i>=p-1?sum/p:sum/(i+1);}return s;}
    static double[] rsi(double[] a,int p){int n=a.length;double[] r=new double[n];double ag=0,al=0;for(int i=1;i<n;i++){double ch=a[i]-a[i-1];double g=Math.max(0,ch),ls=Math.max(0,-ch);if(i<=p){ag+=g;al+=ls;if(i==p){ag/=p;al/=p;r[i]=al==0?100:100-100/(1+ag/al);}else r[i]=50;}else{ag=(ag*(p-1)+g)/p;al=(al*(p-1)+ls)/p;r[i]=al==0?100:100-100/(1+ag/al);}}r[0]=50;return r;}
    static double[] atr(double[] h,double[] l,double[] c,int p){int n=h.length;double[] tr=new double[n];for(int i=0;i<n;i++){if(i==0){tr[i]=h[i]-l[i];continue;}tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));}double[] a=new double[n];double prev=tr[0];for(int i=0;i<n;i++){prev= i==0?tr[0]:(prev*(p-1)+tr[i])/p;a[i]=prev;}return a;}
    static double[] kama(double[] a,int er,int fast,int slow){int n=a.length;double[] k=new double[n];double fsc=2.0/(fast+1),ssc=2.0/(slow+1);k[0]=a[0];for(int i=1;i<n;i++){int s=Math.max(0,i-er);double change=Math.abs(a[i]-a[s]);double vol=0;for(int j=s+1;j<=i;j++)vol+=Math.abs(a[j]-a[j-1]);double effr= vol>0?change/vol:0;double sc=Math.pow(effr*(fsc-ssc)+ssc,2);k[i]=k[i-1]+sc*(a[i]-k[i-1]);}return k;}
    static double[] supertrendDir(double[] h,double[] l,double[] c,int p,double mult){int n=h.length;double[] a=atr(h,l,c,p);double[] dir=new double[n];double[] up=new double[n],dn=new double[n];dir[0]=1;for(int i=0;i<n;i++){double mid=(h[i]+l[i])/2;double bu=mid+mult*a[i],bl=mid-mult*a[i];if(i==0){up[i]=bu;dn[i]=bl;dir[i]=1;continue;}up[i]= (bu<up[i-1]||c[i-1]>up[i-1])?bu:up[i-1];dn[i]= (bl>dn[i-1]||c[i-1]<dn[i-1])?bl:dn[i-1];if(c[i]>up[i-1])dir[i]=1;else if(c[i]<dn[i-1])dir[i]=-1;else dir[i]=dir[i-1];}return dir;}

    // ===================== STRATEGY =====================
    static long SPLIT = Instant.parse("2026-04-23T00:00:00Z").getEpochSecond(); // ~70% train / 30% test
    static class Cfg{int tf;double rsiBuy,rsiSell,tpAtr,slAtr;int minConf,maxHoldMin;boolean rthOnly,entryNextOpen;double costPts;String family;
        boolean hardTrendGate,beStop,avoidOpen30; double beTrigAtr; int tpMode; // 0=ATR,1=VWAP,2=BBmid
        boolean confirmEntry,structStop,rangeOnly; int structLook=5; double structBufAtr=0.3,maxSlAtr=3.0,minSlAtr=0.8;
        public String toString(){return String.format("tf=%dm %s rsiB=%.0f tp%s=%.2f sl=%s cf>=%d%s%s%s",tf,family,rsiBuy,(tpMode==0?"A":tpMode==1?"V":"M"),tpAtr,(structStop?"STR":String.format("%.1f",slAtr)),minConf,(hardTrendGate?" G":""),(confirmEntry?" C":""),(beStop?" BE":""));} }
    static class Res{Cfg cfg;int trades,wins;double grossW,grossL,pnlPts,maxLoss,maxDD;
        int[] splT=new int[2],splW=new int[2]; double[] splGW=new double[2],splGL=new double[2];
        TreeMap<String,int[]> month=new TreeMap<>();
        TreeMap<String,double[]> mStat=new TreeMap<>(); // [wins,total,grossW,grossL] per month
        double wr(){return trades>0?100.0*wins/trades:0;} double pf(){return grossL>0?grossW/grossL:(grossW>0?99:0);} double expPts(){return trades>0?pnlPts/trades:0;}
        double avgW(){return wins>0?grossW/wins:0;} double avgL(){int losses=trades-wins;return losses>0?grossL/losses:0;}
        double splWr(int s){return splT[s]>0?100.0*splW[s]/splT[s]:0;} double splPf(int s){return splGL[s]>0?splGW[s]/splGL[s]:(splGW[s]>0?99:0);}
        int monthsMeet(double thr,int minT){int k=0;for(var e:month.entrySet()){int[] v=e.getValue();if(v[1]>=minT&&100.0*v[0]/v[1]>=thr)k++;}return k;}
        int monthsTot(int minT){int k=0;for(var v:month.values())if(v[1]>=minT)k++;return k;} }

    static double runningEq=0, peakEq=0;
    static Res run(TF t,Ind x,Cfg cfg){
        Res res=new Res(); res.cfg=cfg; int n=t.n; runningEq=0; peakEq=0;
        for(int i=205;i<n;i++){
            if(t.rollFlag[i]) continue;
            int mi=t.mIdx[i];
            int hm=etH[mi]*60+etMin[mi];
            if(cfg.rthOnly){ if(hm<9*60+35||hm>15*60+30) continue; }
            if(cfg.avoidOpen30 && hm<10*60+5) continue;
            int sig;
            if(cfg.confirmEntry){
                sig=0;
                int s1=signal(x,i-1,cfg), s2=signal(x,i-2,cfg);
                boolean longSetup = s1>0||s2>0, shortSetup = s1<0||s2<0;
                boolean confLong  = t.c[i]>t.o[i] && t.c[i]>t.c[i-1];          // bullish reversal bar
                boolean confShort = t.c[i]<t.o[i] && t.c[i]<t.c[i-1];
                if(longSetup && confLong) sig=+1; else if(shortSetup && confShort) sig=-1;
            } else { sig=signal(x,i,cfg); }
            if(sig==0) continue;
            double a=x.atr[i]; if(a<=0) continue;
            if(t.endIdx[i]+1>=N) continue;
            double entry= cfg.entryNextOpen ? o[t.endIdx[i]+1] : t.c[i];
            double tpAtr= sig>0? entry+cfg.tpAtr*a : entry-cfg.tpAtr*a;
            double tp;
            if(cfg.tpMode==1){ double m=x.vwap[i]; tp= sig>0? Math.max(m, entry+0.25*a) : Math.min(m, entry-0.25*a); }
            else if(cfg.tpMode==2){ double m=x.bbMid[i]; tp= sig>0? Math.max(m, entry+0.25*a) : Math.min(m, entry-0.25*a); }
            else tp=tpAtr;
            double sl;
            if(cfg.structStop){
                int a0=Math.max(0,i-cfg.structLook);
                double swLo=t.l[a0],swHi=t.h[a0]; for(int j=a0;j<=i;j++){swLo=Math.min(swLo,t.l[j]);swHi=Math.max(swHi,t.h[j]);}
                if(sig>0){ sl=swLo-cfg.structBufAtr*a; double d=entry-sl; d=Math.max(cfg.minSlAtr*a,Math.min(cfg.maxSlAtr*a,d)); sl=entry-d; }
                else     { sl=swHi+cfg.structBufAtr*a; double d=sl-entry; d=Math.max(cfg.minSlAtr*a,Math.min(cfg.maxSlAtr*a,d)); sl=entry+d; }
            } else { sl= sig>0? entry-cfg.slAtr*a : entry+cfg.slAtr*a; }
            int startM=t.endIdx[i]+1;
            double beArm=cfg.beTrigAtr*a; boolean armed=false;
            double exit=Double.NaN; long entryDay=etDayKey[mi]; int held=0; int exitM=startM;
            for(int m=startM;m<N;m++){
                held++;
                // EOD flat (RTH close 16:00 ET) or new day
                if(etDayKey[m]!=entryDay || (etH[m]*60+etMin[m])>=16*60){ exit=o[m]; exitM=m; break; }
                if(!cm[m].equals(cm[startM])){ exit=o[m]; exitM=m; break; } // roll guard
                if(cfg.beStop && !armed){ double fav= sig>0? h[m]-entry : entry-l[m]; if(fav>=beArm){ armed=true; sl= entry + (sig>0? 0 : 0); } }
                if(sig>0){ boolean hitSL=l[m]<=sl, hitTP=h[m]>=tp;
                    if(hitSL){exit=sl;exitM=m;break;} if(hitTP){exit=tp;exitM=m;break;} }
                else { boolean hitSL=h[m]>=sl, hitTP=l[m]<=tp;
                    if(hitSL){exit=sl;exitM=m;break;} if(hitTP){exit=tp;exitM=m;break;} }
                if(held>=cfg.maxHoldMin){ exit=c[m]; exitM=m; break; }
            }
            if(Double.isNaN(exit)){ exit=c[N-1]; exitM=N-1; }
            double pts= (sig>0? exit-entry : entry-exit) - cfg.costPts; // NET of costs
            res.trades++; res.pnlPts+=pts;
            int sp = ts[mi]<SPLIT?0:1; res.splT[sp]++;
            if(pts>0){res.wins++;res.grossW+=pts;res.splW[sp]++;res.splGW[sp]+=pts;} else {res.grossL+=-pts;res.splGL[sp]+=-pts;}
            if(pts<res.maxLoss)res.maxLoss=pts;
            runningEq+=pts; if(runningEq>peakEq)peakEq=runningEq; if(peakEq-runningEq>res.maxDD)res.maxDD=peakEq-runningEq;
            String mk=monthKey(mi);
            res.month.computeIfAbsent(mk,kk->new int[2]); int[] mv=res.month.get(mk); mv[1]++; if(pts>0)mv[0]++;
            double[] ms=res.mStat.computeIfAbsent(mk,kk->new double[4]); ms[1]++; if(pts>0){ms[0]++;ms[2]+=pts;} else ms[3]+=-pts;
            // jump i past exit to avoid overlap
            // find TF bar containing exitM
            while(i+1<n && t.endIdx[i]<exitM) i++;
        }
        return res;
    }

    // Family A: mean reversion at band extreme + k-of-N confirmations (9 indicators in pool)
    static int signal(Ind x,int i,Cfg cfg){
        if(cfg.rangeOnly && x.regime[i]!=0) return 0; // mean-reversion only in non-trending regime
        boolean longGate = x.bbPctB[i]<=0.05 || x.close[i]<=x.vwLo[i];
        boolean shortGate= x.bbPctB[i]>=0.95 || x.close[i]>=x.vwUp[i];
        if(longGate){
            if(cfg.hardTrendGate && x.regime[i]==-2) return 0; // don't catch falling knife
            int k=0;
            if(x.rsi[i]<=cfg.rsiBuy)k++;
            if(x.stK[i]<=25 && x.stCrossUp[i])k++;
            if(x.wt1[i]<=-53 && x.wtCrossUp[i])k++;
            if(x.cmf[i]>=-0.05 || x.deltaOsc[i]>0)k++;          // money flow / delta
            if(x.pdPct[i]<=0.4)k++;                              // SMC discount
            if(x.regime[i]>=-1)k++;
            if(x.stDir[i]==1)k++;                                // Supertrend support
            if(x.macdHist[i]>x.macdHist[i-1])k++;               // MACD momentum turning up
            if(x.smcBias[i]>=0)k++;                              // SMC structure not bearish
            if(k>=cfg.minConf) return +1;
        }
        if(shortGate){
            if(cfg.hardTrendGate && x.regime[i]==2) return 0;
            int k=0;
            if(x.rsi[i]>=cfg.rsiSell)k++;
            if(x.stK[i]>=75 && x.stCrossDn[i])k++;
            if(x.wt1[i]>=53 && x.wtCrossDn[i])k++;
            if(x.cmf[i]<=0.05 || x.deltaOsc[i]<0)k++;
            if(x.pdPct[i]>=0.6)k++;
            if(x.regime[i]<=1)k++;
            if(x.stDir[i]==-1)k++;
            if(x.macdHist[i]<x.macdHist[i-1])k++;
            if(x.smcBias[i]<=0)k++;
            if(k>=cfg.minConf) return -1;
        }
        return 0;
    }

    // ===================== REPORT =====================
    static void report(List<Res> all){
        // keep only net-profitable; rank by months meeting 70% net, then test-set WR, then PF
        all.removeIf(r->r.pf()<1.0);
        all.sort((p,q)->{
            int a=q.monthsMeet(70,5)-p.monthsMeet(70,5); if(a!=0)return a;
            int b=Double.compare(q.splWr(1),p.splWr(1)); if(b!=0)return b; // out-of-sample WR
            return Double.compare(q.pf(),p.pf());
        });
        System.out.println("\n===== TOP CONFIGS (NET of 1.0pt cost, next-open fills) =====");
        System.out.println("split: TRAIN=before 2026-04-23, TEST=after  | WR/PF are NET");
        System.out.printf("%-46s %5s %5s %5s %6s %6s %6s %3s %5s %5s %5s %5s%n",
            "config","trd","WR%","PF","avgW","avgL","maxDD","m70","trnWR","trnPF","tstWR","tstPF");
        int shown=0;
        for(Res r: all){
            if(shown++>=18) break;
            System.out.printf("%-46s %5d %5.1f %5.2f %6.2f %6.2f %6.0f %d/%d %5.1f %5.2f %5.1f %5.2f%n",
                r.cfg.toString(), r.trades, r.wr(), r.pf(), r.avgW(), r.avgL(), r.maxDD,
                r.monthsMeet(70,5), r.monthsTot(5),
                r.splWr(0), r.splPf(0), r.splWr(1), r.splPf(1));
        }
        System.out.println("\nNet-profitable configs (>=30 trd, PF>=1): "+all.size());
        if(!all.isEmpty()){ Res b=all.get(0); System.out.print("\nBEST monthly NET-WR: ");
            for(var e:b.month.entrySet()){int[] v=e.getValue();System.out.print(e.getKey()+":"+(v[1]>0?Math.round(100.0*v[0]/v[1]):0)+"%("+v[1]+") ");}
            System.out.println("\nBEST cfg: "+b.cfg+"  netExp="+String.format("%.2f",b.expPts())+"pt  maxLoss="+String.format("%.1f",b.maxLoss)+"pt"); }
    }
}
