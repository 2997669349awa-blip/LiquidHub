import * as React from "react";

import { useQuery } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import { useTranslation } from "react-i18next";

import { cn } from "~/lib/utils";
import api from "~/services/api";
import { useChatInputStore } from "~/stores";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";

export interface SkillPickerButtonProps {
  draftKey?: string | null;
  disabled?: boolean;
  className?: string;
}

interface SkillDto {
  name: string;
  description: string;
  enabled: boolean;
}

/**
 * 输入框里的技能选择器。
 * 选中后，本条消息只会向模型暴露该技能（后端按消息限定），避免 AI 在多个技能里乱调。
 */
export function SkillPickerButton({ draftKey, disabled = false, className }: SkillPickerButtonProps) {
  const { t } = useTranslation("input");
  const skill = useChatInputStore(
    React.useCallback(
      (state) => (draftKey ? (state.drafts[draftKey]?.skill ?? null) : null),
      [draftKey],
    ),
  );
  const setSkill = useChatInputStore((state) => state.setSkill);
  const [open, setOpen] = React.useState(false);

  const { data } = useQuery({
    queryKey: ["skills"],
    queryFn: () => api.get<SkillDto[]>("skills"),
    staleTime: 60_000,
  });

  const skills = React.useMemo(
    () => (Array.isArray(data) ? data.filter((item) => item && typeof item.name === "string") : []),
    [data],
  );

  const canUse = Boolean(draftKey && !disabled);
  if (skills.length === 0) {
    return null;
  }

  return (
    <Popover open={open} onOpenChange={(next) => setOpen(canUse && next)}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          aria-label={t("skill.title")}
          title={skill ?? t("skill.title")}
          variant="ghost"
          size="sm"
          disabled={!canUse}
          className={cn(
            "h-8 gap-1 rounded-full px-2 text-muted-foreground hover:text-foreground",
            skill && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          <Sparkles className="size-4" />
          {skill ? <span className="max-w-[96px] truncate text-xs">{skill}</span> : null}
        </Button>
      </PopoverTrigger>

      <PopoverContent
        side="top"
        align="start"
        sideOffset={8}
        className="max-h-[var(--radix-popover-content-available-height)] w-[min(92vw,20rem)] space-y-2 overflow-y-auto p-3"
      >
        <PopoverTitle className="text-sm">{t("skill.title")}</PopoverTitle>
        <p className="text-muted-foreground text-xs">{t("skill.hint")}</p>

        <button
          type="button"
          onClick={() => {
            if (draftKey) setSkill(draftKey, null);
            setOpen(false);
          }}
          className={cn(
            "hover:bg-muted flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm transition",
            !skill && "bg-primary/5",
          )}
        >
          <span className="truncate">{t("skill.default")}</span>
        </button>

        {skills.map((item) => {
          const checked = skill === item.name;
          return (
            <button
              key={item.name}
              type="button"
              onClick={() => {
                if (draftKey) setSkill(draftKey, checked ? null : item.name);
                setOpen(false);
              }}
              className={cn(
                "hover:bg-muted flex w-full flex-col items-start gap-0.5 rounded-lg px-2 py-2 text-left transition",
                checked && "bg-primary/5",
              )}
            >
              <span className="w-full truncate text-sm font-medium">{item.name}</span>
              {item.description ? (
                <span className="text-muted-foreground line-clamp-2 w-full text-xs">
                  {item.description}
                </span>
              ) : null}
            </button>
          );
        })}
      </PopoverContent>
    </Popover>
  );
}
