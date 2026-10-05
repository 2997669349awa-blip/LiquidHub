import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import { Switch } from "~/components/ui/switch";
import { ScrollArea } from "~/components/ui/scroll-area";
import api from "~/services/api";
import * as manage from "~/services/manage";

type TabId = "general" | "assistants" | "skills" | "files" | "phone";

const TABS: { id: TabId; label: string }[] = [
  { id: "general", label: "通用" },
  { id: "assistants", label: "助手" },
  { id: "skills", label: "技能" },
  { id: "files", label: "文件" },
  { id: "phone", label: "手机" },
];

const REASONING_LEVELS = ["AUTO", "OFF", "LOW", "MEDIUM", "HIGH"];

interface SkillSummary {
  name: string;
  description: string;
  enabled: boolean;
}

function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: React.ReactNode;
  hint?: string;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-sm font-medium">{label}</span>
      {children}
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </label>
  );
}

function Toggle({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-1.5">
      <span className="text-sm">{label}</span>
      <Switch checked={checked} onCheckedChange={onChange} />
    </div>
  );
}

function nativeSelectClass() {
  return "h-9 w-full rounded-md border border-input bg-transparent px-2 text-sm shadow-xs outline-none focus-visible:ring-2 focus-visible:ring-ring";
}

export function SettingsDrawer({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [tab, setTab] = useState<TabId>("general");

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[86svh] max-w-5xl flex-col gap-0 overflow-hidden p-0">
        <DialogHeader className="border-b px-5 py-3">
          <DialogTitle>设置</DialogTitle>
        </DialogHeader>
        <div className="flex min-h-0 flex-1">
          <nav className="w-32 shrink-0 border-r p-2">
            {TABS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setTab(item.id)}
                className={`mb-1 w-full rounded-md px-3 py-2 text-left text-sm transition ${
                  tab === item.id ? "bg-muted font-medium" : "hover:bg-muted/60"
                }`}
              >
                {item.label}
              </button>
            ))}
          </nav>
          <div className="min-h-0 flex-1">
            {tab === "general" && <GeneralTab />}
            {tab === "assistants" && <AssistantsTab />}
            {tab === "skills" && <SkillsTab />}
            {tab === "files" && <FilesTab />}
            {tab === "phone" && <PhoneTab />}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function GeneralTab() {
  const [settings, setSettings] = useState<manage.GeneralSettingsDto | null>(null);
  const [providers, setProviders] = useState<manage.ProviderDto[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        const [s, p] = await Promise.all([manage.getGeneralSettings(), manage.listProviders()]);
        setSettings(s);
        setProviders(p);
      } catch (error) {
        toast.error(`加载失败：${(error as Error).message}`);
      }
    })();
  }, []);

  const chatModels = useMemo(
    () => providers.flatMap((p) => p.models).filter((m) => m.type === "CHAT"),
    [providers],
  );
  const imageModels = useMemo(
    () => providers.flatMap((p) => p.models).filter((m) => m.type === "IMAGE"),
    [providers],
  );

  const save = useCallback(
    async (patch: manage.GeneralSettingsPatch) => {
      setSaving(true);
      try {
        const next = await manage.updateGeneralSettings(patch);
        setSettings(next);
        toast.success("已保存");
      } catch (error) {
        toast.error(`保存失败：${(error as Error).message}`);
      } finally {
        setSaving(false);
      }
    },
    [],
  );

  if (!settings) {
    return <div className="p-6 text-sm text-muted-foreground">加载中…</div>;
  }

  const modelOption = (m: manage.ModelDto) => (
    <option key={m.id} value={m.id}>
      {m.displayName || m.modelId}
    </option>
  );

  return (
    <ScrollArea className="h-full">
      <div className="space-y-1 p-5">
        <h3 className="mb-2 text-sm font-semibold text-muted-foreground">外观与行为</h3>
        <Toggle
          label="动态取色"
          checked={settings.dynamicColor}
          onChange={(v) => void save({ dynamicColor: v })}
        />
        <Toggle
          label="开发者模式"
          checked={settings.developerMode}
          onChange={(v) => void save({ developerMode: v })}
        />
        <Toggle
          label="聊天建议"
          checked={settings.enableSuggestion}
          onChange={(v) => void save({ enableSuggestion: v })}
        />
        <Field label="主题 ID">
          <Input
            defaultValue={settings.themeId}
            onBlur={(e) => {
              if (e.target.value !== settings.themeId) void save({ themeId: e.target.value });
            }}
          />
        </Field>

        <h3 className="mb-2 mt-4 text-sm font-semibold text-muted-foreground">默认模型</h3>
        <Field label="默认对话模型">
          <select
            className={nativeSelectClass()}
            value={settings.chatModelId}
            onChange={(e) => void save({ chatModelId: e.target.value })}
          >
            <option value="">（未设置）</option>
            {chatModels.map(modelOption)}
          </select>
        </Field>
        <Field label="快速模型">
          <select
            className={nativeSelectClass()}
            value={settings.fastModelId}
            onChange={(e) => void save({ fastModelId: e.target.value })}
          >
            <option value="">（未设置）</option>
            {chatModels.map(modelOption)}
          </select>
        </Field>
        <Field label="图片生成模型">
          <select
            className={nativeSelectClass()}
            value={settings.imageGenerationModelId}
            onChange={(e) => void save({ imageGenerationModelId: e.target.value })}
          >
            <option value="">（未设置）</option>
            {imageModels.map(modelOption)}
          </select>
        </Field>
        <Field label="翻译模型">
          <select
            className={nativeSelectClass()}
            value={settings.translateModeId}
            onChange={(e) => void save({ translateModeId: e.target.value })}
          >
            <option value="">（未设置）</option>
            {chatModels.map(modelOption)}
          </select>
        </Field>
        <Field label="OCR 模型">
          <select
            className={nativeSelectClass()}
            value={settings.ocrModelId}
            onChange={(e) => void save({ ocrModelId: e.target.value })}
          >
            <option value="">（未设置）</option>
            {chatModels.map(modelOption)}
          </select>
        </Field>
        <Field label="上下文压缩模型">
          <select
            className={nativeSelectClass()}
            value={settings.compressModelId}
            onChange={(e) => void save({ compressModelId: e.target.value })}
          >
            <option value="">（未设置）</option>
            {chatModels.map(modelOption)}
          </select>
        </Field>
        {saving ? <div className="pt-2 text-xs text-muted-foreground">保存中…</div> : null}
      </div>
    </ScrollArea>
  );
}

function AssistantsTab() {
  const [assistants, setAssistants] = useState<manage.AssistantDto[]>([]);
  const [providers, setProviders] = useState<manage.ProviderDto[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<manage.AssistantDto | null>(null);
  const [newName, setNewName] = useState("");
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async (selectId?: string) => {
    const [list, provs] = await Promise.all([manage.listAssistants(), manage.listProviders()]);
    setAssistants(list);
    setProviders(provs);
    const next = list.find((a) => a.id === selectId) ?? list[0] ?? null;
    setSelectedId(next?.id ?? null);
    setDraft(next ? { ...next } : null);
  }, []);

  useEffect(() => {
    void reload().catch((error) => toast.error(`加载失败：${(error as Error).message}`));
  }, [reload]);

  const chatModels = useMemo(
    () => providers.flatMap((p) => p.models).filter((m) => m.type === "CHAT"),
    [providers],
  );

  const set = <K extends keyof manage.AssistantDto>(key: K, value: manage.AssistantDto[K]) =>
    setDraft((prev) => (prev ? { ...prev, [key]: value } : prev));

  const save = useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    try {
      const saved = await manage.updateAssistant(draft.id, {
        name: draft.name,
        systemPrompt: draft.systemPrompt,
        chatModelId: draft.chatModelId,
        temperature: draft.temperature,
        topP: draft.topP,
        contextMessageLimit: draft.contextMessageLimit,
        maxTokens: draft.maxTokens,
        streamOutput: draft.streamOutput,
        enableMemory: draft.enableMemory,
        useGlobalMemory: draft.useGlobalMemory,
        enableWebSearch: draft.enableWebSearch,
        enableRecentChatsReference: draft.enableRecentChatsReference,
        reasoningLevel: draft.reasoningLevel,
        useGradientBackground: draft.useGradientBackground,
      });
      setDraft({ ...saved });
      setAssistants((prev) => prev.map((a) => (a.id === saved.id ? saved : a)));
      toast.success("已保存");
    } catch (error) {
      toast.error(`保存失败：${(error as Error).message}`);
    } finally {
      setBusy(false);
    }
  }, [draft]);

  const create = useCallback(async () => {
    const name = newName.trim();
    if (!name) return;
    try {
      const created = await manage.createAssistant(name);
      setNewName("");
      await reload(created.id);
      toast.success("已新建助手");
    } catch (error) {
      toast.error(`新建失败：${(error as Error).message}`);
    }
  }, [newName, reload]);

  const clone = useCallback(async () => {
    if (!draft) return;
    try {
      const created = await manage.cloneAssistant(draft.id);
      await reload(created.id);
      toast.success("已复制助手");
    } catch (error) {
      toast.error(`复制失败：${(error as Error).message}`);
    }
  }, [draft, reload]);

  const remove = useCallback(async () => {
    if (!draft) return;
    if (!window.confirm(`删除助手「${draft.name}」？`)) return;
    try {
      await manage.deleteAssistant(draft.id);
      await reload();
      toast.success("已删除");
    } catch (error) {
      toast.error(`删除失败：${(error as Error).message}`);
    }
  }, [draft, reload]);

  return (
    <div className="flex h-full min-h-0">
      <div className="flex w-52 shrink-0 flex-col border-r">
        <div className="flex gap-1 border-b p-2">
          <Input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="新助手名称"
            className="h-8 text-sm"
          />
          <Button type="button" size="sm" onClick={() => void create()}>
            新建
          </Button>
        </div>
        <ScrollArea className="flex-1">
          <div className="p-2">
            {assistants.map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => {
                  setSelectedId(a.id);
                  setDraft({ ...a });
                }}
                className={`mb-1 w-full truncate rounded-md px-2 py-1.5 text-left text-sm transition ${
                  selectedId === a.id ? "bg-muted font-medium" : "hover:bg-muted/60"
                }`}
              >
                {a.name || "未命名"}
              </button>
            ))}
          </div>
        </ScrollArea>
      </div>

      <div className="min-h-0 flex-1">
        {!draft ? (
          <div className="p-6 text-sm text-muted-foreground">请选择或新建助手</div>
        ) : (
          <ScrollArea className="h-full">
            <div className="space-y-3 p-5">
              <Field label="名称">
                <Input value={draft.name} onChange={(e) => set("name", e.target.value)} />
              </Field>
              <Field label="系统提示词">
                <Textarea
                  value={draft.systemPrompt}
                  onChange={(e) => set("systemPrompt", e.target.value)}
                  rows={6}
                />
              </Field>
              <Field label="对话模型">
                <select
                  className={nativeSelectClass()}
                  value={draft.chatModelId ?? ""}
                  onChange={(e) => set("chatModelId", e.target.value || null)}
                >
                  <option value="">（使用全局默认）</option>
                  {chatModels.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.displayName || m.modelId}
                    </option>
                  ))}
                </select>
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="温度">
                  <Input
                    type="number"
                    step="0.1"
                    value={draft.temperature ?? ""}
                    onChange={(e) =>
                      set("temperature", e.target.value === "" ? null : Number(e.target.value))
                    }
                  />
                </Field>
                <Field label="Top P">
                  <Input
                    type="number"
                    step="0.05"
                    value={draft.topP ?? ""}
                    onChange={(e) =>
                      set("topP", e.target.value === "" ? null : Number(e.target.value))
                    }
                  />
                </Field>
                <Field label="上下文消息上限（0=不限）">
                  <Input
                    type="number"
                    value={draft.contextMessageLimit}
                    onChange={(e) => set("contextMessageLimit", Number(e.target.value) || 0)}
                  />
                </Field>
                <Field label="最大 Token">
                  <Input
                    type="number"
                    value={draft.maxTokens ?? ""}
                    onChange={(e) =>
                      set("maxTokens", e.target.value === "" ? null : Number(e.target.value))
                    }
                  />
                </Field>
              </div>
              <Field label="思考等级">
                <select
                  className={nativeSelectClass()}
                  value={draft.reasoningLevel}
                  onChange={(e) => set("reasoningLevel", e.target.value)}
                >
                  {REASONING_LEVELS.map((level) => (
                    <option key={level} value={level}>
                      {level}
                    </option>
                  ))}
                </select>
              </Field>
              <div className="space-y-0.5 rounded-md border p-3">
                <Toggle
                  label="流式输出"
                  checked={draft.streamOutput}
                  onChange={(v) => set("streamOutput", v)}
                />
                <Toggle
                  label="启用记忆"
                  checked={draft.enableMemory}
                  onChange={(v) => set("enableMemory", v)}
                />
                <Toggle
                  label="使用全局记忆"
                  checked={draft.useGlobalMemory}
                  onChange={(v) => set("useGlobalMemory", v)}
                />
                <Toggle
                  label="启用网络搜索"
                  checked={draft.enableWebSearch}
                  onChange={(v) => set("enableWebSearch", v)}
                />
                <Toggle
                  label="引用最近聊天"
                  checked={draft.enableRecentChatsReference}
                  onChange={(v) => set("enableRecentChatsReference", v)}
                />
                <Toggle
                  label="渐变背景"
                  checked={draft.useGradientBackground}
                  onChange={(v) => set("useGradientBackground", v)}
                />
              </div>
              <div className="flex gap-2 pt-1">
                <Button type="button" disabled={busy} onClick={() => void save()}>
                  保存
                </Button>
                <Button type="button" variant="outline" onClick={() => void clone()}>
                  复制
                </Button>
                <Button type="button" variant="destructive" onClick={() => void remove()}>
                  删除
                </Button>
              </div>
            </div>
          </ScrollArea>
        )}
      </div>
    </div>
  );
}

function SkillsTab() {
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState("");
  const [newName, setNewName] = useState("");
  const [dirty, setDirty] = useState(false);

  const reload = useCallback(async (selectName?: string | null) => {
    const list = await api.get<SkillSummary[]>("skills");
    setSkills(list);
    const name = selectName !== undefined ? selectName : selected;
    if (name && list.some((s) => s.name === name)) {
      const detail = await manage.getSkill(name);
      setSelected(name);
      setContent(detail.content);
      setDirty(false);
    } else if (list.length > 0) {
      const detail = await manage.getSkill(list[0].name);
      setSelected(list[0].name);
      setContent(detail.content);
      setDirty(false);
    } else {
      setSelected(null);
      setContent("");
      setDirty(false);
    }
  }, [selected]);

  useEffect(() => {
    void reload(null).catch((error) => toast.error(`加载失败：${(error as Error).message}`));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openSkill = useCallback(async (name: string) => {
    try {
      const detail = await manage.getSkill(name);
      setSelected(name);
      setContent(detail.content);
      setDirty(false);
    } catch (error) {
      toast.error(`打开失败：${(error as Error).message}`);
    }
  }, []);

  const save = useCallback(async () => {
    if (!selected) return;
    try {
      await manage.saveSkill(selected, content);
      setDirty(false);
      toast.success("已保存");
      await reload(selected);
    } catch (error) {
      toast.error(`保存失败：${(error as Error).message}`);
    }
  }, [selected, content, reload]);

  const create = useCallback(async () => {
    const name = newName.trim();
    if (!name) return;
    const template = `---\nname: ${name}\ndescription: \n---\n\n`;
    try {
      await manage.saveSkill(name, template);
      setNewName("");
      await reload(name);
      toast.success("已新建技能");
    } catch (error) {
      toast.error(`新建失败：${(error as Error).message}`);
    }
  }, [newName, reload]);

  const remove = useCallback(async () => {
    if (!selected) return;
    if (!window.confirm(`删除技能「${selected}」？`)) return;
    try {
      await manage.deleteSkill(selected);
      toast.success("已删除");
      await reload(null);
    } catch (error) {
      toast.error(`删除失败：${(error as Error).message}`);
    }
  }, [selected, reload]);

  return (
    <div className="flex h-full min-h-0">
      <div className="flex w-52 shrink-0 flex-col border-r">
        <div className="flex gap-1 border-b p-2">
          <Input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="新技能名称"
            className="h-8 text-sm"
          />
          <Button type="button" size="sm" onClick={() => void create()}>
            新建
          </Button>
        </div>
        <ScrollArea className="flex-1">
          <div className="p-2">
            {skills.map((s) => (
              <button
                key={s.name}
                type="button"
                onClick={() => void openSkill(s.name)}
                className={`mb-1 w-full truncate rounded-md px-2 py-1.5 text-left text-sm transition ${
                  selected === s.name ? "bg-muted font-medium" : "hover:bg-muted/60"
                }`}
              >
                {s.name}
              </button>
            ))}
            {skills.length === 0 && (
              <div className="px-2 py-4 text-center text-xs text-muted-foreground">暂无技能</div>
            )}
          </div>
        </ScrollArea>
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        {!selected ? (
          <div className="p-6 text-sm text-muted-foreground">请选择或新建技能</div>
        ) : (
          <>
            <div className="flex items-center justify-between border-b px-4 py-2">
              <span className="text-sm font-medium">{selected}</span>
              <div className="flex gap-2">
                <Button type="button" size="sm" disabled={!dirty} onClick={() => void save()}>
                  保存
                </Button>
                <Button type="button" size="sm" variant="destructive" onClick={() => void remove()}>
                  删除
                </Button>
              </div>
            </div>
            <Textarea
              value={content}
              onChange={(e) => {
                setContent(e.target.value);
                setDirty(true);
              }}
              className="min-h-0 flex-1 resize-none rounded-none border-0 font-mono text-xs shadow-none focus-visible:ring-0"
            />
          </>
        )}
      </div>
    </div>
  );
}

function FilesTab() {
  const [files, setFiles] = useState<manage.FileItemDto[]>([]);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setFiles(await manage.listFiles());
    } catch (error) {
      toast.error(`加载失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const remove = useCallback(
    async (id: number) => {
      try {
        await api.delete(`files/${id}`);
        toast.success("已删除");
        await reload();
      } catch (error) {
        toast.error(`删除失败：${(error as Error).message}`);
      }
    },
    [reload],
  );

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  };

  return (
    <ScrollArea className="h-full">
      <div className="p-4">
        {loading ? (
          <div className="text-sm text-muted-foreground">加载中…</div>
        ) : files.length === 0 ? (
          <div className="text-sm text-muted-foreground">暂无文件</div>
        ) : (
          <div className="space-y-1">
            {files.map((file) => (
              <div
                key={file.id}
                className="flex items-center gap-3 rounded-md border px-3 py-2"
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm">{file.displayName}</div>
                  <div className="text-xs text-muted-foreground">
                    {file.folder} · {formatSize(file.sizeBytes)}
                  </div>
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="destructive"
                  onClick={() => void remove(file.id)}
                >
                  删除
                </Button>
              </div>
            ))}
          </div>
        )}
      </div>
    </ScrollArea>
  );
}

function PhoneTab() {
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [text, setText] = useState("");
  const [clickText, setClickText] = useState("");
  const [pkg, setPkg] = useState("");
  const [shot, setShot] = useState<string | null>(null);

  const run = useCallback(
    async (action: string, args: Record<string, string> = {}, label?: string) => {
      setBusy(true);
      setStatus("等待手机端确认…");
      try {
        const res = await manage.phoneAction(action, args, label);
        if (action === "screenshot") {
          setShot(`data:image/png;base64,${res.result}`);
          setStatus("截图成功");
        } else {
          setStatus(res.result || "成功");
        }
      } catch (error) {
        setStatus(`失败：${(error as Error).message}`);
      } finally {
        setBusy(false);
      }
    },
    [],
  );

  return (
    <ScrollArea className="h-full">
      <div className="space-y-4 p-5">
        <p className="text-sm text-muted-foreground">
          操作会先推送到手机，需在手机上点「允许」后才会执行；30 秒内未确认即视为拒绝。
        </p>

        <div className="flex flex-wrap gap-2">
          <Button size="sm" disabled={busy} onClick={() => void run("home")}>
            返回桌面
          </Button>
          <Button size="sm" disabled={busy} onClick={() => void run("back")}>
            后退
          </Button>
          <Button size="sm" disabled={busy} onClick={() => void run("recents")}>
            最近任务
          </Button>
          <Button size="sm" disabled={busy} onClick={() => void run("notifications")}>
            通知栏
          </Button>
          <Button size="sm" disabled={busy} onClick={() => void run("read_screen")}>
            读取屏幕
          </Button>
          <Button size="sm" disabled={busy} onClick={() => void run("screenshot")}>
            截屏
          </Button>
        </div>

        <Field label="输入文本">
          <div className="flex gap-2">
            <Input value={text} onChange={(e) => setText(e.target.value)} placeholder="要输入的文字" />
            <Button size="sm" disabled={busy || !text} onClick={() => void run("text", { text })}>
              发送
            </Button>
          </div>
        </Field>

        <Field label="点击文字">
          <div className="flex gap-2">
            <Input
              value={clickText}
              onChange={(e) => setClickText(e.target.value)}
              placeholder="界面上的文字"
            />
            <Button
              size="sm"
              disabled={busy || !clickText}
              onClick={() => void run("click_text", { text: clickText })}
            >
              点击
            </Button>
          </div>
        </Field>

        <Field label="打开应用（包名）">
          <div className="flex gap-2">
            <Input value={pkg} onChange={(e) => setPkg(e.target.value)} placeholder="com.example.app" />
            <Button size="sm" disabled={busy || !pkg} onClick={() => void run("open_app", { package: pkg })}>
              打开
            </Button>
          </div>
        </Field>

        {status ? <div className="text-sm text-muted-foreground">{status}</div> : null}

        {shot ? (
          <img src={shot} alt="screenshot" className="max-h-80 w-auto rounded-md border" />
        ) : null}
      </div>
    </ScrollArea>
  );
}
