'use client';

import { useRef, useCallback, useEffect, useState, MutableRefObject } from 'react';
import type { IChartApi, ISeriesApi, Time, CandlestickData } from 'lightweight-charts';
import { api } from '@/app/lib/api';

interface UseScrollBackParams {
  chart: IChartApi | null;
  candleSeries: ISeriesApi<'Candlestick', Time> | null;
  instrument: string;
  timeframe: string;
  allBarsRef: MutableRefObject<CandlestickData<Time>[]>;
}

interface UseScrollBackResult {
  loading: boolean;
  noMoreData: boolean;
}

/**
 * Hook that enables infinite scroll-back on a lightweight-charts candlestick chart.
 *
 * When the user scrolls left and the visible logical range's `from` drops below 10,
 * this hook fetches older candles via the v2 endpoint, merges them into allBarsRef,
 * and re-sets the data on the series without causing the chart to jump.
 */
export function useScrollBack({
  chart,
  candleSeries,
  instrument,
  timeframe,
  allBarsRef,
}: UseScrollBackParams): UseScrollBackResult {
  const [loading, setLoading] = useState(false);
  const [noMoreData, setNoMoreData] = useState(false);
  const loadingRef = useRef(false);
  const noMoreDataRef = useRef(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset state when instrument or timeframe changes
  useEffect(() => {
    setNoMoreData(false);
    noMoreDataRef.current = false;
    setLoading(false);
    loadingRef.current = false;
  }, [instrument, timeframe]);

  const fetchOlderBars = useCallback(async () => {
    if (loadingRef.current || noMoreDataRef.current) return;
    if (!candleSeries || !chart) return;

    const currentBars = allBarsRef.current;
    if (currentBars.length === 0) return;

    const oldestTime = currentBars[0].time as number;

    loadingRef.current = true;
    setLoading(true);

    try {
      const response = await api.getCandlesV2(instrument, timeframe, {
        to: oldestTime,
        countBack: 300,
      });

      if (response.noData || !response.bars || response.bars.length === 0) {
        noMoreDataRef.current = true;
        setNoMoreData(true);
        return;
      }

      // Convert new bars to CandlestickData format
      const newBars: CandlestickData<Time>[] = response.bars.map(c => ({
        time: (c.time > 1e10 ? Math.floor(c.time / 1000) : Math.floor(c.time)) as Time,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      }));

      // Merge: prepend new bars, sort by time, deduplicate
      const merged = [...newBars, ...currentBars]
        .sort((a, b) => (a.time as number) - (b.time as number))
        .filter((item, idx, arr) => idx === arr.length - 1 || item.time !== arr[idx + 1].time);

      // Save current visible logical range before setData
      const savedRange = chart.timeScale().getVisibleLogicalRange();

      // Calculate how many new bars were actually added (for range offset)
      const addedCount = merged.length - currentBars.length;

      // Update the ref and set data
      allBarsRef.current = merged;
      candleSeries.setData(merged);

      // Restore visible range shifted by the number of prepended bars
      if (savedRange && addedCount > 0) {
        chart.timeScale().setVisibleLogicalRange({
          from: savedRange.from + addedCount,
          to: savedRange.to + addedCount,
        });
      }
    } catch {
      // Network error — silently ignore, user can retry by scrolling again
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }, [chart, candleSeries, instrument, timeframe, allBarsRef]);

  useEffect(() => {
    if (!chart) return;

    const onRangeChange = (range: { from: number; to: number } | null) => {
      if (!range) return;
      if (range.from >= 10) return;
      if (loadingRef.current || noMoreDataRef.current) return;

      // Debounce 200ms
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }
      debounceRef.current = setTimeout(() => {
        debounceRef.current = null;
        void fetchOlderBars();
      }, 200);
    };

    chart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange);

    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(onRangeChange);
    };
  }, [chart, fetchOlderBars]);

  return { loading, noMoreData };
}
