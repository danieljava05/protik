import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ProTik test harness",
  description: "A small client for exercising the ProTik API.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
