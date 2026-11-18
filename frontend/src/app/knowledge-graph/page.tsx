import type { Metadata } from "next";
import KnowledgeGraphClient from "./KnowledgeGraphClient";

export const metadata: Metadata = { title: "知识图谱 · SignalForge" };
export default function KnowledgeGraphPage() { return <KnowledgeGraphClient />; }
