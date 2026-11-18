import type { Metadata } from "next";
import JavaResearchClient from "./JavaResearchClient";

export const metadata: Metadata = { title: "研究任务 · SignalForge" };
export default function JavaResearchPage() {
  return <JavaResearchClient />;
}
