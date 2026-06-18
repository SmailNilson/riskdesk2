package com.riskdesk.research;

import com.riskdesk.domain.engine.indicators.*;
import com.riskdesk.domain.engine.smc.SessionPdArrayCalculator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * Optimization sweep on the PRODUCTION indicator backtest (range-only mean reversion).
 * Production calculate() lists are front-trimmed/offset -> aligned into per-candle arrays, then a fast
 * sim sweeps {tf, sl, tp, conf, rsi} with intrabar 1m exits. Picks the deployable config optimized on
 * the indicators that ship. Self-skips without the CSV. Run: mvn -q -Dtest=MnqProductionSweepTest test
 */
public class MnqProductionSweepTest {
    static final ZoneId NY = ZoneId.of("America/New_York");
    static final double PV = 2.0, COST_PTS = 1.0;
    static final long SPLIT = Instant.parse("2026-04-23T00:00:00Z").getEpochSecond();

    // 1m arrays
    int n1; long[] ts; double[] O,H,L,C; long[] V; int[] etH,etMin; long[] etDay; String[] mkey;

    @Test
    public void productionSweep() throws IOException {
        Path csv = Files.exists(Paths.get("research/mnq_1m.csv")) ? Paths.get("research/mnq_1m.csv") : Paths.get("/tmp/mnq_1m.csv");
        Assumptions.assumeTrue(Files.exists(csv), "candle CSV not present — skipping sweep");
        load(csv);

        List<R> all = new ArrayList<>();
        for (int tf : new int[]{10,12,15,20}) {
            Agg a = aggregate(tf);
            Feat f = features(a);
            for (double sl : new double[]{2.0,2.5,3.0,3.5})
            for (double tp : new double[]{0.75,1.0})
            for (int conf : new int[]{3,4})
            for (int[] rp : new int[][]{{35,65},{30,70}}) {
                R r = sim(a, f, tf, sl, tp, conf, rp[0], rp[1]);
                if (r.trades >= 45) all.add(r);
            }
        }
        // rank: most months >=70% (>=5 trd), then OOS WR, then PF; require PF>=1.2
        all.removeIf(r -> r.pf() < 1.2);
        all.sort((p,q) -> {
            int a = q.m70() - p.m70(); if (a!=0) return a;
            int b = Double.compare(q.splWr(1), p.splWr(1)); if (b!=0) return b;
            return Double.compare(q.pf(), p.pf());
        });
        System.out.println("\n===== PRODUCTION-INDICATOR SWEEP (range-only mean reversion, NET 1pt, next-open) =====");
        System.out.printf("%-40s %4s %5s %5s %6s %6s %4s %5s %5s%n","config","trd","WR%","PF","exp","maxDD","m70","trnWR","tstWR");
        int shown=0; R best=null;
        for (R r : all) { if (shown++>=15) break; if (best==null) best=r;
            System.out.printf("tf=%-2dm sl=%.1f tp=%.2f cf>=%d rsi%d/%-3d %4d %5.1f %5.2f %6.1f %6.0f %d/%d %5.1f %5.1f%n",
                r.tf,r.sl,r.tp,r.conf,(int)r.rsiB,(int)r.rsiS, r.trades,r.wr(),r.pf(),r.expPts(),r.maxDD,r.m70(),r.mtot(),r.splWr(0),r.splWr(1));
        }
        System.out.println("net-profitable (PF>=1.2, trd>=45): "+all.size());
        if (best!=null) {
            System.out.print("\nBEST monthly WR: "); for (var e: best.month.entrySet()){int[] v=e.getValue();System.out.print(e.getKey().substring(5)+":"+(v[1]>0?Math.round(100.0*v[0]/v[1]):0)+"%("+v[1]+") ");}
            System.out.print("\nBEST monthly $ (1 contract, net): "); for (var e: best.monthPnl.entrySet()){System.out.printf("%s:$%.0f  ",e.getKey().substring(5),e.getValue()*PV);}
            System.out.printf("%nBEST: tf=%dm sl=%.1f tp=%.2f cf>=%d rsi=%d/%d -> %.1f%% WR, PF %.2f, %d trd, net $%.0f (1 contract, ~5.5mo) | avgWin=$%.0f avgLoss=$%.0f maxDD=$%.0f%n",
                best.tf,best.sl,best.tp,best.conf,(int)best.rsiB,(int)best.rsiS,best.wr(),best.pf(),best.trades,best.pnl*PV,
                (best.wins>0?best.gW/best.wins:0)*PV, ((best.trades-best.wins)>0?best.gL/(best.trades-best.wins):0)*PV, best.maxDD*PV);
            org.junit.jupiter.api.Assertions.assertTrue(best.wr()>=70.0, "best production WR <70%");
        }
    }

    // ---- production features aligned to candle index ----
    static class Feat { double[] pctB,vwLo,vwUp,rsi,stK,cmf,macdHist,atr; String[] stCross,wtSig,wtCross,zone; boolean[] supUp,trending; }
    Feat features(Agg a) {
        List<Candle> c = a.c; int n=c.size();
        var bb = new BollingerBandsIndicator().calculate(c);
        var bbT= new BollingerBandsIndicator().calculateTrend(c);
        var vw = new VWAPIndicator().calculate(c);
        var rsiL = new RSIIndicator().calculate(c);
        var wt = new WaveTrendIndicator().calculate(c);
        var st = new StochasticIndicator().calculate(c);
        var cmfL= new ChaikinIndicator().calculateCMF(c);
        var macd= new MACDIndicator().calculate(c);
        var sup = new SupertrendIndicator().calculate(c);
        var e9 = new EMAIndicator(9).calculate(c); var e50=new EMAIndicator(50).calculate(c); var e200=new EMAIndicator(200).calculate(c);
        var det = new MarketRegimeDetector(); var pd = new SessionPdArrayCalculator();
        Feat f = new Feat();
        f.pctB=alignD(bb,n,r->r.pct().doubleValue()); f.vwLo=alignD(vw,n,r->r.lowerBand().doubleValue()); f.vwUp=alignD(vw,n,r->r.upperBand().doubleValue());
        f.rsi=alignBD(rsiL,n); f.stK=alignD(st,n,r->r.k().doubleValue()); f.cmf=alignBD(cmfL,n);
        f.macdHist=alignD(macd,n,r->r.histogram().doubleValue());
        f.stCross=alignS(st,n,r->r.crossover()); f.wtSig=alignS(wt,n,r->r.signal()); f.wtCross=alignS(wt,n,r->r.crossover());
        f.supUp=alignB(sup,n,r->r.isUptrend());
        double[] e9a=alignBD(e9,n),e50a=alignBD(e50,n),e200a=alignBD(e200,n); boolean[] bbExp=alignB(bbT,n,r->r.expanding());
        f.trending=new boolean[n]; f.zone=new String[n];
        for (int i=0;i<n;i++){
            if (Double.isNaN(e9a[i])||Double.isNaN(e50a[i])||Double.isNaN(e200a[i])) f.trending[i]=true;
            else { String reg=det.detect(BigDecimal.valueOf(e9a[i]),BigDecimal.valueOf(e50a[i]),BigDecimal.valueOf(e200a[i]),bbExp[i]);
                   f.trending[i]= MarketRegimeDetector.TRENDING_UP.equals(reg)||MarketRegimeDetector.TRENDING_DOWN.equals(reg); }
            double rHi=c.get(i).getHigh().doubleValue(), rLo=c.get(i).getLow().doubleValue();
            for (int j=Math.max(0,i-19);j<=i;j++){ rHi=Math.max(rHi,c.get(j).getHigh().doubleValue()); rLo=Math.min(rLo,c.get(j).getLow().doubleValue()); }
            f.zone[i]=pd.compute(BigDecimal.valueOf(rHi),BigDecimal.valueOf(rLo),c.get(i).getClose()).zone();
        }
        f.atr = atrWilder(a, 14);
        return f;
    }

    R sim(Agg a, Feat f, int tf, double slAtr, double tpAtr, int conf, double rsiB, double rsiS) {
        List<Candle> c=a.c; int n=c.size(); R res=new R(); res.tf=tf;res.sl=slAtr;res.tp=tpAtr;res.conf=conf;res.rsiB=rsiB;res.rsiS=rsiS;
        double eq=0,peak=0;
        for (int i=205;i<n;i++){
            if (f.trending[i]) continue;                       // range-only
            if (Double.isNaN(f.pctB[i])||Double.isNaN(f.vwLo[i])||Double.isNaN(f.rsi[i])||Double.isNaN(f.stK[i])||Double.isNaN(f.cmf[i])||Double.isNaN(f.macdHist[i])||Double.isNaN(f.macdHist[i-1])||Double.isNaN(f.atr[i])) continue;
            int mi=a.endIdx[i]; int hm=etH[mi]*60+etMin[mi]; if (hm<9*60+35||hm>15*60+30) continue;
            if (a.endIdx[i]+1>=n1) continue;
            double close=c.get(i).getClose().doubleValue();
            boolean lg = f.pctB[i]<=0.05 || close<=f.vwLo[i];
            boolean sg = f.pctB[i]>=0.95 || close>=f.vwUp[i];
            int sig=0;
            if (lg){ int k=0;
                if (f.rsi[i]<=rsiB)k++; if (f.stK[i]<=25 && "BULLISH_CROSS".equals(f.stCross[i]))k++;
                if ("OVERSOLD".equals(f.wtSig[i]) && "BULLISH_CROSS".equals(f.wtCross[i]))k++; if (f.cmf[i]>=-0.05)k++;
                if ("DISCOUNT".equals(f.zone[i]))k++; if (f.supUp[i])k++; if (f.macdHist[i]>f.macdHist[i-1])k++;
                if (k>=conf) sig=1; }
            if (sig==0 && sg){ int k=0;
                if (f.rsi[i]>=rsiS)k++; if (f.stK[i]>=75 && "BEARISH_CROSS".equals(f.stCross[i]))k++;
                if ("OVERBOUGHT".equals(f.wtSig[i]) && "BEARISH_CROSS".equals(f.wtCross[i]))k++; if (f.cmf[i]<=0.05)k++;
                if ("PREMIUM".equals(f.zone[i]))k++; if (!f.supUp[i])k++; if (f.macdHist[i]<f.macdHist[i-1])k++;
                if (k>=conf) sig=-1; }
            if (sig==0) continue;
            double atr=f.atr[i]; int ei=a.endIdx[i]; double entry=O[ei+1];
            double tp= sig>0? entry+tpAtr*atr : entry-tpAtr*atr, sl= sig>0? entry-slAtr*atr : entry+slAtr*atr;
            long day=etDay[mi]; double exit=Double.NaN; int held=0,exitM=ei+1;
            for (int m=ei+1;m<n1;m++){ held++;
                if (etDay[m]!=day || (etH[m]*60+etMin[m])>=16*60){ exit=O[m];exitM=m;break; }
                if (sig>0){ if(L[m]<=sl){exit=sl;exitM=m;break;} if(H[m]>=tp){exit=tp;exitM=m;break;} }
                else { if(H[m]>=sl){exit=sl;exitM=m;break;} if(L[m]<=tp){exit=tp;exitM=m;break;} }
                if (held>=tf*30){ exit=C[m];exitM=m;break; } }
            if (Double.isNaN(exit)){ exit=C[n1-1]; exitM=n1-1; }
            double pts=(sig>0? exit-entry : entry-exit)-COST_PTS;
            res.trades++; res.pnl+=pts; int sp= ts[mi]<SPLIT?0:1; res.splT[sp]++;
            if(pts>0){res.wins++;res.gW+=pts;res.splW[sp]++;res.splGW[sp]+=pts;} else {res.gL+=-pts;res.splGL[sp]+=-pts;}
            eq+=pts; if(eq>peak)peak=eq; if(peak-eq>res.maxDD)res.maxDD=peak-eq;
            int[] mv=res.month.computeIfAbsent(mkey[mi],z->new int[2]); mv[1]++; if(pts>0)mv[0]++;
            res.monthPnl.merge(mkey[mi],pts,Double::sum);
            while (i+1<n && a.endIdx[i]<exitM) i++;
        }
        return res;
    }

    // ---- aggregation ----
    static class Agg { List<Candle> c; int[] endIdx; }
    Agg aggregate(int tfMin){
        int tfSec=tfMin*60; List<Candle> out=new ArrayList<>(); List<Integer> ends=new ArrayList<>();
        long cur=Long.MIN_VALUE; int s=0;
        for (int i=0;i<n1;i++){ long b=ts[i]/tfSec; if (b!=cur){ if(i>0) flush(out,ends,s,i-1); cur=b; s=i; } }
        flush(out,ends,s,n1-1);
        Agg a=new Agg(); a.c=out; a.endIdx=ends.stream().mapToInt(Integer::intValue).toArray(); return a;
    }
    void flush(List<Candle> out,List<Integer> ends,int s,int z){
        double hi=H[s],lo=L[s]; long v=0; for(int i=s;i<=z;i++){hi=Math.max(hi,H[i]);lo=Math.min(lo,L[i]);v+=V[i];}
        out.add(new Candle(Instrument.MNQ,"agg",Instant.ofEpochSecond(ts[z]),bd(O[s]),bd(hi),bd(lo),bd(C[z]),Math.max(1,v)));
        ends.add(z);
    }
    double[] atrWilder(Agg a,int p){ int n=a.c.size(); double[] tr=new double[n],at=new double[n];
        for(int i=0;i<n;i++){ double h=a.c.get(i).getHigh().doubleValue(),l=a.c.get(i).getLow().doubleValue();
            if(i==0){tr[i]=h-l;} else {double pc=a.c.get(i-1).getClose().doubleValue(); tr[i]=Math.max(h-l,Math.max(Math.abs(h-pc),Math.abs(l-pc)));} }
        double prev=tr[0]; for(int i=0;i<n;i++){ prev= i==0?tr[0]:(prev*(p-1)+tr[i])/p; at[i]= i>=p?prev:Double.NaN; } return at; }

    // ---- data ----
    void load(Path csv) throws IOException {
        List<String> lines=Files.readAllLines(csv); n1=lines.size();
        ts=new long[n1];O=new double[n1];H=new double[n1];L=new double[n1];C=new double[n1];V=new long[n1];etH=new int[n1];etMin=new int[n1];etDay=new long[n1];mkey=new String[n1];
        for(int i=0;i<n1;i++){ String[] f=lines.get(i).split(",");
            ts[i]=Long.parseLong(f[0]);O[i]=Double.parseDouble(f[1]);H[i]=Double.parseDouble(f[2]);L[i]=Double.parseDouble(f[3]);C[i]=Double.parseDouble(f[4]);V[i]=Long.parseLong(f[5]);
            ZonedDateTime z=Instant.ofEpochSecond(ts[i]).atZone(NY); etH[i]=z.getHour();etMin[i]=z.getMinute();
            etDay[i]=z.getYear()*10000L+z.getMonthValue()*100L+z.getDayOfMonth(); mkey[i]=String.format("%04d-%02d",z.getYear(),z.getMonthValue()); }
    }

    // ---- result ----
    static class R { int tf,conf,trades,wins; double sl,tp,rsiB,rsiS,gW,gL,pnl,maxDD;
        int[] splT=new int[2],splW=new int[2]; double[] splGW=new double[2],splGL=new double[2];
        TreeMap<String,int[]> month=new TreeMap<>(); TreeMap<String,Double> monthPnl=new TreeMap<>();
        double wr(){return trades>0?100.0*wins/trades:0;} double pf(){return gL>0?gW/gL:(gW>0?99:0);} double expPts(){return trades>0?pnl/trades:0;}
        double splWr(int s){return splT[s]>0?100.0*splW[s]/splT[s]:0;}
        int m70(){int k=0;for(int[] v:month.values())if(v[1]>=5&&100.0*v[0]/v[1]>=70)k++;return k;}
        int mtot(){int k=0;for(int[] v:month.values())if(v[1]>=5)k++;return k;} }

    // ---- aligners (production lists are front-trimmed; right-align by length) ----
    static <T> double[] alignD(List<T> Lst,int n,ToDoubleFunction<T> f){ double[] a=new double[n]; int off=n-Lst.size();
        for(int i=0;i<n;i++){int k=i-off; a[i]=(k>=0&&k<Lst.size())?f.applyAsDouble(Lst.get(k)):Double.NaN;} return a; }
    static double[] alignBD(List<BigDecimal> Lst,int n){ return alignD(Lst,n,x->x==null?Double.NaN:x.doubleValue()); }
    static <T> String[] alignS(List<T> Lst,int n,Function<T,String> f){ String[] a=new String[n]; int off=n-Lst.size();
        for(int i=0;i<n;i++){int k=i-off; a[i]=(k>=0&&k<Lst.size())?f.apply(Lst.get(k)):null;} return a; }
    static <T> boolean[] alignB(List<T> Lst,int n,Predicate<T> f){ boolean[] a=new boolean[n]; int off=n-Lst.size();
        for(int i=0;i<n;i++){int k=i-off; a[i]=(k>=0&&k<Lst.size())&&f.test(Lst.get(k));} return a; }
    static BigDecimal bd(double x){ return BigDecimal.valueOf(x); }
}
