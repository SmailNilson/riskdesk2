import type { Metadata } from 'next';
import { Inter, JetBrains_Mono } from 'next/font/google';
import './globals.css';

const inter = Inter({
  subsets: ['latin'],
  weight: ['400', '500', '600', '700'],
  variable: '--rd-font-inter',
  display: 'swap',
});

const jetbrains = JetBrains_Mono({
  subsets: ['latin'],
  weight: ['400', '500', '600', '700'],
  variable: '--rd-font-jetbrains',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'RiskDesk — Trader Terminal',
  description: 'Real-time futures trader terminal with AI mentor and SMC overlays.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`dark ${inter.variable} ${jetbrains.variable}`}>
      <body className="bg-zinc-950 text-white antialiased">{children}</body>
    </html>
  );
}
