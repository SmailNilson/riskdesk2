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

/**
 * PARITY check for the range-only mean-reversion MNQ strategy (research/RESULTS.md, Champion A).
 * Recomputes the strategy using PRODUCTION indicator classes (BollingerBandsIndicator,
 * VWAPIndicator, WaveTrendIndicator, StochasticIndicator, ChaikinIndicator, MACDIndicator,
 * SupertrendIndicator, EMAIndicator, MarketRegimeDetector, SessionPdArrayCalculator) on real 1m
 * candles, resolving SL/TP intrabar on the 1m series. Confirms the standalone harness edge
 * (~84.8% WR / PF 2.10 on 15m) reproduces with the system's own indicators.
 *
 * Self-skips if the candle CSV is absent (so it is a no-op in CI). Run locally:
 *   mvn -q -Dtest=MnqRangeReversionParityTest test
 */
public class MnqRangeReversionParityTest {

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final double PV = 2.0;            // MNQ $ / point
    static final double COST_PTS = 1.0;      // round-trip cost in points

    @Test
    public void rangeOnlyMeanReversion_parity() throws IOException {
        Path csv = Files.exists(Paths.get("research/mnq_1m.csv")) ? Paths.get("research/mnq_1m.csv")
                 : Paths.get("/tmp/mnq_1m.csv");
        Assumptions.assumeTrue(Files.exists(csv), "candle CSV not present — skipping parity test");

        // ---- load 1m ----
        List<String> lines = Files.readAllLines(csv);
        int n1 = lines.size();
        long[] ts = new long[n1]; double[] o=new double[n1],h=new double[n1],l=new double[n1],c=new double[n1];
        long[] vol=new long[n1]; int[] etH=new int[n1],etMin=new int[n1]; long[] etDay=new long[n1]; String[] mk=new String[n1];
        List<Candle> c1 = new ArrayList<>(n1);
        for (int i=0;i<n1;i++){
            String[] f = lines.get(i).split(",");
            ts[i]=Long.parseLong(f[0]); o[i]=Double.parseDouble(f[1]); h[i]=Double.parseDouble(f[2]);
            l[i]=Double.parseDouble(f[3]); c[i]=Double.parseDouble(f[4]); vol[i]=Long.parseLong(f[5]);
            ZonedDateTime z = Instant.ofEpochSecond(ts[i]).atZone(NY);
            etH[i]=z.getHour(); etMin[i]=z.getMinute(); etDay[i]=z.getYear()*10000L+z.getMonthValue()*100L+z.getDayOfMonth();
            mk[i]=String.format("%04d-%02d", z.getYear(), z.getMonthValue());
            c1.add(new Candle(Instrument.MNQ,"1m",Instant.ofEpochSecond(ts[i]),
                    bd(o[i]),bd(h[i]),bd(l[i]),bd(c[i]),vol[i]));
        }

        // ---- aggregate to TF + map each TF bar -> last 1m index ----
        int tfMin=Integer.getInteger("tf",15), tfSec=tfMin*60;
        double slAtr=Double.parseDouble(System.getProperty("sl","3.0"));
        List<Candle> c15=new ArrayList<>(); List<Integer> endIdx=new ArrayList<>(); List<Integer> miEt=new ArrayList<>();
        long cur=Long.MIN_VALUE; int s=0;
        for (int i=0;i<n1;i++){
            long b=ts[i]/tfSec;
            if (b!=cur){ if(i>0) flush(c1,c15,endIdx,miEt,s,i-1); cur=b; s=i; }
        }
        flush(c1,c15,endIdx,miEt,s,n1-1);
        int n=c15.size();

        // ---- production indicators on 15m ----
        List<BollingerBandsIndicator.BBResult> bb = new BollingerBandsIndicator().calculate(c15);
        List<BollingerBandsIndicator.BBTrendResult> bbT = new BollingerBandsIndicator().calculateTrend(c15);
        List<VWAPIndicator.VWAPResult> vw = new VWAPIndicator().calculate(c15);
        List<BigDecimal> rsi = new RSIIndicator().calculate(c15);
        List<WaveTrendIndicator.WaveTrendResult> wt = new WaveTrendIndicator().calculate(c15);
        List<StochasticIndicator.StochasticResult> st = new StochasticIndicator().calculate(c15);
        List<BigDecimal> cmf = new ChaikinIndicator().calculateCMF(c15);
        List<MACDIndicator.MACDResult> macd = new MACDIndicator().calculate(c15);
        List<SupertrendIndicator.SupertrendResult> sup = new SupertrendIndicator().calculate(c15);
        List<BigDecimal> e9=new EMAIndicator(9).calculate(c15), e50=new EMAIndicator(50).calculate(c15), e200=new EMAIndicator(200).calculate(c15);
        MarketRegimeDetector regimeDet = new MarketRegimeDetector();
        SessionPdArrayCalculator pdCalc = new SessionPdArrayCalculator();

        // ---- strategy (Champion A: RSI<=35/>=65, TP0.75ATR, SL3.0ATR, conf>=3, RANGE-ONLY) ----
        int trades=0,wins=0; double gW=0,gL=0,pnl=0,maxLoss=0;
        TreeMap<String,int[]> month=new TreeMap<>();

        for (int i=205;i<n;i++){
            // production indicator lists are front-trimmed & offset -> right-align by length via at()
            BigDecimal _e9=at(e9,i,n),_e50=at(e50,i,n),_e200=at(e200,i,n);
            BollingerBandsIndicator.BBTrendResult _bbT=at(bbT,i,n);
            if (_e9==null||_e50==null||_e200==null||_bbT==null) continue;
            String regime = regimeDet.detect(_e9,_e50,_e200,_bbT.expanding());
            if (MarketRegimeDetector.TRENDING_UP.equals(regime)||MarketRegimeDetector.TRENDING_DOWN.equals(regime)) continue;

            BollingerBandsIndicator.BBResult _bb=at(bb,i,n); VWAPIndicator.VWAPResult _vw=at(vw,i,n);
            BigDecimal _rsi=at(rsi,i,n); WaveTrendIndicator.WaveTrendResult _wt=at(wt,i,n);
            StochasticIndicator.StochasticResult _st=at(st,i,n);
            MACDIndicator.MACDResult _macd=at(macd,i,n),_macd1=at(macd,i-1,n);
            SupertrendIndicator.SupertrendResult _sup=at(sup,i,n); BigDecimal _cmf=at(cmf,i,n);
            if (_bb==null||_vw==null||_rsi==null||_wt==null||_st==null||_macd==null||_macd1==null||_sup==null||_cmf==null) continue;

            int mi=miEt.get(i); int hm=etH[mi]*60+etMin[mi];
            if (hm<9*60+35||hm>15*60+30) continue;       // RTH only
            int ei=endIdx.get(i); if (ei+1>=n1) continue;

            double close=c15.get(i).getClose().doubleValue();
            double pctB=d(_bb.pct()), vwLo=d(_vw.lowerBand()), vwUp=d(_vw.upperBand());
            // rolling 20-bar dealing range -> PD zone
            double rHi=c15.get(i).getHigh().doubleValue(), rLo=c15.get(i).getLow().doubleValue();
            for (int j=Math.max(0,i-19);j<=i;j++){ rHi=Math.max(rHi,c15.get(j).getHigh().doubleValue()); rLo=Math.min(rLo,c15.get(j).getLow().doubleValue()); }
            String zone = pdCalc.compute(bd(rHi),bd(rLo),bd(close)).zone();

            boolean longGate = pctB<=0.05 || close<=vwLo;
            boolean shortGate= pctB>=0.95 || close>=vwUp;
            int sig=0;
            if (longGate){
                int k=0;
                if (d(_rsi)<=35) k++;
                if (d(_st.k())<=25 && "BULLISH_CROSS".equals(_st.crossover())) k++;
                if ("OVERSOLD".equals(_wt.signal()) && "BULLISH_CROSS".equals(_wt.crossover())) k++;
                if (d(_cmf)>=-0.05) k++;
                if ("DISCOUNT".equals(zone)) k++;
                if (_sup.isUptrend()) k++;
                if (d(_macd.histogram())>d(_macd1.histogram())) k++;
                if (k>=3) sig=1;
            }
            if (sig==0 && shortGate){
                int k=0;
                if (d(_rsi)>=65) k++;
                if (d(_st.k())>=75 && "BEARISH_CROSS".equals(_st.crossover())) k++;
                if ("OVERBOUGHT".equals(_wt.signal()) && "BEARISH_CROSS".equals(_wt.crossover())) k++;
                if (d(_cmf)<=0.05) k++;
                if ("PREMIUM".equals(zone)) k++;
                if (!_sup.isUptrend()) k++;
                if (d(_macd.histogram())<d(_macd1.histogram())) k++;
                if (k>=3) sig=-1;
            }
            if (sig==0) continue;

            double atr = AtrCalculator.compute(c15.subList(0,i+1),14).doubleValue();
            if (atr<=0) continue;
            double entry = o[ei+1];                       // next-bar (1m) open
            double tp = sig>0? entry+0.75*atr : entry-0.75*atr;
            double sl = sig>0? entry-slAtr*atr : entry+slAtr*atr;
            long entryDay=etDay[mi]; double exit=Double.NaN; int held=0; int exitM=ei+1;
            for (int m=ei+1;m<n1;m++){
                held++;
                if (etDay[m]!=entryDay || (etH[m]*60+etMin[m])>=16*60){ exit=o[m]; exitM=m; break; }
                if (sig>0){ if(l[m]<=sl){exit=sl;exitM=m;break;} if(h[m]>=tp){exit=tp;exitM=m;break;} }
                else      { if(h[m]>=sl){exit=sl;exitM=m;break;} if(l[m]<=tp){exit=tp;exitM=m;break;} }
                if (held>=tfMin*30){ exit=c[m]; exitM=m; break; }
            }
            if (Double.isNaN(exit)){ exit=c[n1-1]; exitM=n1-1; }
            double pts=(sig>0? exit-entry : entry-exit) - COST_PTS;
            trades++; pnl+=pts; if(pts>0){wins++;gW+=pts;} else gL+=-pts; if(pts<maxLoss)maxLoss=pts;
            int[] mv=month.computeIfAbsent(mk[mi],z->new int[2]); mv[1]++; if(pts>0)mv[0]++;
            while (i+1<n && endIdx.get(i)<exitM) i++;     // non-overlap
        }

        double wr = trades>0?100.0*wins/trades:0;
        double pf = gL>0?gW/gL:(gW>0?99:0);
        StringBuilder mb=new StringBuilder(); int m70=0,mtot=0;
        for (var e:month.entrySet()){ int[] v=e.getValue(); int w= v[1]>0?(int)Math.round(100.0*v[0]/v[1]):0;
            mb.append(e.getKey().substring(5)).append(":").append(w).append("%(").append(v[1]).append(") ");
            if(v[1]>=5){mtot++; if(w>=70)m70++;} }

        System.out.println("\n==== PARITY (production indicators) — "+tfMin+"m range-only mean reversion (sl="+slAtr+"ATR) ====");
        System.out.printf("trades=%d  WR=%.1f%%  PF=%.2f  exp=%.2fpt  maxLoss=%.0fpt  net$=%.0f%n",
                trades, wr, pf, trades>0?pnl/trades:0, maxLoss, pnl*PV);
        System.out.println("monthly WR: "+mb+" | months>=70%: "+m70+"/"+mtot);
        System.out.println("(harness reference: ~84.8% WR / PF 2.10 / 92 trades)");

        org.junit.jupiter.api.Assertions.assertTrue(trades>=40, "too few trades — parity inconclusive ("+trades+")");
        org.junit.jupiter.api.Assertions.assertTrue(wr>=70.0, "production-indicator WR below 70% — parity FAILED: "+wr);
    }

    // aggregate 1m [a..z] into one 15m Candle
    static void flush(List<Candle> c1,List<Candle> out,List<Integer> endIdx,List<Integer> miEt,int a,int z){
        BigDecimal open=c1.get(a).getOpen(), close=c1.get(z).getClose();
        BigDecimal hi=c1.get(a).getHigh(), lo=c1.get(a).getLow(); long v=0;
        for (int i=a;i<=z;i++){ if(c1.get(i).getHigh().compareTo(hi)>0)hi=c1.get(i).getHigh();
            if(c1.get(i).getLow().compareTo(lo)<0)lo=c1.get(i).getLow(); v+=c1.get(i).getVolume(); }
        out.add(new Candle(Instrument.MNQ,"15m",c1.get(z).getTimestamp(),open,hi,lo,close,v));
        endIdx.add(z); miEt.add(z);
    }
    static BigDecimal bd(double x){ return BigDecimal.valueOf(x); }
    static double d(BigDecimal x){ return x==null?Double.NaN:x.doubleValue(); }
    /** Production calculate() lists are front-trimmed but end at the last candle; right-align by length. */
    static <T> T at(List<T> L,int i,int n){ int k=i-(n-L.size()); return (k>=0 && k<L.size())? L.get(k): null; }
}
