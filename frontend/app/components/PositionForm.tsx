'use client';

import { useState } from 'react';
import { api, CreatePositionRequest } from '@/app/lib/api';

const INSTRUMENTS = ['MCL', 'MGC', 'E6', 'MNQ'];

interface Props { onCreated: () => void }

export default function PositionForm({ onCreated }: Props) {
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const [form, setForm] = useState<Partial<CreatePositionRequest>>({
    instrument: 'MCL',
    side: 'LONG',
    quantity: 1,
  });

  const set = (k: string, v: unknown) => setForm(prev => ({ ...prev, [k]: v }));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.instrument || !form.side || !form.quantity || !form.entryPrice) return;
    setSubmitting(true);
    try {
      await api.openPosition(form as CreatePositionRequest);
      setOpen(false);
      setForm({ instrument: 'MCL', side: 'LONG', quantity: 1 });
      onCreated();
    } catch (err) {
      alert(`Failed: ${err}`);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="px-3 py-1.5 bg-emerald-700 hover:bg-emerald-600 text-white text-xs font-semibold rounded transition-colors"
      >
        + Open Position
      </button>

      {open && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center">
          <div className="bg-zinc-900 border border-zinc-700 rounded-xl w-full max-w-md p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-5">
              <h2 className="text-white font-semibold">Open Position</h2>
              <button onClick={() => setOpen(false)} className="text-zinc-500 hover:text-zinc-300 text-xl">✕</button>
            </div>

            <form onSubmit={submit} className="space-y-3">
              {/* Instrument + Side */}
              <div className="grid grid-cols-2 gap-3">
                <Field label="Instrument">
                  <select
                    value={form.instrument}
                    onChange={e => set('instrument', e.target.value)}
                    className={inputCls}
                  >
                    {INSTRUMENTS.map(i => <option key={i}>{i}</option>)}
                  </select>
                </Field>
                <Field label="Side">
                  <div className="flex rounded overflow-hidden border border-zinc-700">
                    {(['LONG', 'SHORT'] as const).map(s => (
                      <button
                        key={s}
                        type="button"
                        onClick={() => set('side', s)}
                        className={`flex-1 py-2 text-xs font-semibold transition-colors ${
                          form.side === s
                            ? s === 'LONG' ? 'bg-emerald-700 text-white' : 'bg-red-700 text-white'
                            : 'bg-zinc-800 text-zinc-400 hover:bg-zinc-700'
                        }`}
                      >{s}</button>
                    ))}
                  </div>
                </Field>
              </div>

              {/* Quantity + Entry */}
              <div className="grid grid-cols-2 gap-3">
                <Field label="Quantity">
                  <input type="number" min={1} value={form.quantity ?? ''}
                    onChange={e => set('quantity', parseInt(e.target.value))}
                    className={inputCls} required />
                </Field>
                <Field label="Entry Price">
                  <input type="number" step="0.0001" value={form.entryPrice ?? ''}
                    onChange={e => set('entryPrice', parseFloat(e.target.value))}
                    className={inputCls} required />
                </Field>
              </div>

              {/* Stop + TP */}
              <div className="grid grid-cols-2 gap-3">
                <Field label="Stop Loss">
                  <input type="number" step="0.0001" value={form.stopLoss ?? ''}
                    onChange={e => set('stopLoss', parseFloat(e.target.value))}
                    className={inputCls} />
                </Field>
                <Field label="Take Profit">
                  <input type="number" step="0.0001" value={form.takeProfit ?? ''}
                    onChange={e => set('takeProfit', parseFloat(e.target.value))}
                    className={inputCls} />
                </Field>
              </div>

              {/* Notes */}
              <Field label="Notes">
                <input type="text" value={form.notes ?? ''}
                  onChange={e => set('notes', e.target.value)}
                  placeholder="e.g. Bearish CHoCH on 1h"
                  className={inputCls} />
              </Field>

              <div className="flex gap-2 pt-2">
                <button type="button" onClick={() => setOpen(false)}
                  className="flex-1 py-2 border border-zinc-700 text-zinc-400 hover:text-zinc-200 rounded-lg text-sm transition-colors">
                  Cancel
                </button>
                <button type="submit" disabled={submitting}
                  className={`flex-1 py-2 rounded-lg text-sm font-semibold text-white transition-colors ${
                    form.side === 'SHORT'
                      ? 'bg-red-700 hover:bg-red-600'
                      : 'bg-emerald-700 hover:bg-emerald-600'
                  } disabled:opacity-50`}>
                  {submitting ? 'Opening…' : `${form.side} ${form.instrument}`}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}

const inputCls = 'w-full bg-zinc-800 border border-zinc-700 rounded-lg px-3 py-2 text-sm text-white placeholder-zinc-600 focus:outline-none focus:border-zinc-500';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-[10px] font-medium uppercase tracking-wider text-zinc-500 mb-1">{label}</label>
      {children}
    </div>
  );
}
