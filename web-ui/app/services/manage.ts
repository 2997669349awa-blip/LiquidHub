import api from "./api";

export interface AssistantDto {
  id: string;
  name: string;
  systemPrompt: string;
  chatModelId: string | null;
  temperature: number | null;
  topP: number | null;
  contextMessageLimit: number;
  streamOutput: boolean;
  enableMemory: boolean;
  useGlobalMemory: boolean;
  enableWebSearch: boolean;
  enableRecentChatsReference: boolean;
  maxTokens: number | null;
  reasoningLevel: string;
  useGradientBackground: boolean;
  workspaceId: string | null;
}

export type AssistantPatch = Partial<
  Pick<
    AssistantDto,
    | "name"
    | "systemPrompt"
    | "chatModelId"
    | "temperature"
    | "topP"
    | "contextMessageLimit"
    | "streamOutput"
    | "enableMemory"
    | "useGlobalMemory"
    | "enableWebSearch"
    | "enableRecentChatsReference"
    | "maxTokens"
    | "reasoningLevel"
    | "useGradientBackground"
    | "workspaceId"
  >
>;

export interface ModelDto {
  id: string;
  modelId: string;
  displayName: string;
  type: string;
  tools: string[];
}

export interface ProviderDto {
  id: string;
  name: string;
  enabled: boolean;
  builtIn: boolean;
  type: string;
  apiKey: string;
  baseUrl: string;
  models: ModelDto[];
}

export interface ProviderPatch {
  name?: string;
  enabled?: boolean;
  apiKey?: string;
  baseUrl?: string;
}

export interface GeneralSettingsDto {
  dynamicColor: boolean;
  themeId: string;
  developerMode: boolean;
  enableSuggestion: boolean;
  chatModelId: string;
  fastModelId: string;
  imageGenerationModelId: string;
  translateModeId: string;
  ocrModelId: string;
  compressModelId: string;
  webServerEnabled: boolean;
  webServerPort: number;
  webServerJwtEnabled: boolean;
  webServerLocalhostOnly: boolean;
}

export type GeneralSettingsPatch = Partial<
  Pick<
    GeneralSettingsDto,
    | "dynamicColor"
    | "themeId"
    | "developerMode"
    | "enableSuggestion"
    | "chatModelId"
    | "fastModelId"
    | "imageGenerationModelId"
    | "translateModeId"
    | "ocrModelId"
    | "compressModelId"
  >
>;

export interface SkillDetailDto {
  name: string;
  description: string;
  enabled: boolean;
  content: string;
}

export interface FileItemDto {
  id: number;
  folder: string;
  displayName: string;
  mimeType: string;
  sizeBytes: number;
  relativePath: string;
  createdAt: number;
  updatedAt: number;
}

// Assistants
export const listAssistants = () => api.get<AssistantDto[]>("manage/assistants");
export const createAssistant = (name: string) =>
  api.post<AssistantDto>("manage/assistants", { name });
export const updateAssistant = (id: string, patch: AssistantPatch) =>
  api.post<AssistantDto>(`manage/assistants/${id}`, patch);
export const cloneAssistant = (id: string) =>
  api.post<AssistantDto>(`manage/assistants/${id}/clone`);
export const deleteAssistant = (id: string) => api.delete(`manage/assistants/${id}`);

// Providers
export const listProviders = () => api.get<ProviderDto[]>("manage/providers");
export const updateProvider = (id: string, patch: ProviderPatch) =>
  api.post(`manage/providers/${id}`, patch);

// General settings
export const getGeneralSettings = () => api.get<GeneralSettingsDto>("manage/settings");
export const updateGeneralSettings = (patch: GeneralSettingsPatch) =>
  api.post<GeneralSettingsDto>("manage/settings", patch);

// Skills
export const getSkill = (name: string) =>
  api.get<SkillDetailDto>(`manage/skills/${encodeURIComponent(name)}`);
export const saveSkill = (name: string, content: string) =>
  api.post<SkillDetailDto>("manage/skills", { name, content });
export const deleteSkill = (name: string) =>
  api.delete(`manage/skills/${encodeURIComponent(name)}`);

// Files
export const listFiles = (folder?: string) =>
  api.get<FileItemDto[]>(`manage/files${folder ? `?folder=${encodeURIComponent(folder)}` : ""}`);

// Phone control (requires on-device confirmation)
export interface PhoneActionResult {
  ok: boolean;
  result: string;
}

export const phoneAction = (
  action: string,
  args: Record<string, string> = {},
  label?: string,
) => api.post<PhoneActionResult>("manage/phone/action", { action, args, label: label ?? "" });
