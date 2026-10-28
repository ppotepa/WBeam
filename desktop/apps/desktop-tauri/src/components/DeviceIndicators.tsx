import BatteryCharging from "lucide-solid/icons/battery-charging";
import BatteryFull from "lucide-solid/icons/battery-full";
import BatteryLow from "lucide-solid/icons/battery-low";
import BatteryMedium from "lucide-solid/icons/battery-medium";
import Smartphone from "lucide-solid/icons/smartphone";
import Tablet from "lucide-solid/icons/tablet";

export function BatteryIcon(props: { readonly level: number | null; readonly charging: boolean }) {
  if (props.charging) return <BatteryCharging size={14} />;
  if (props.level === null) return <BatteryMedium size={14} />;
  if (props.level >= 80) return <BatteryFull size={14} />;
  if (props.level >= 30) return <BatteryMedium size={14} />;
  return <BatteryLow size={14} />;
}

export function DeviceTypeIcon(props: { readonly type: string }) {
  return props.type === "Tablet" ? <Tablet size={16} /> : <Smartphone size={16} />;
}

