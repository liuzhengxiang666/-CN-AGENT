import type { ButtonHTMLAttributes, TextareaHTMLAttributes } from "react";
export function Button({
  onPress,
  variant = "secondary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  onPress?: () => void;
  variant?: "primary" | "secondary";
}) {
  return (
    <button
      {...props}
      className={`button ${variant} ${className}`}
      onClick={props.onClick ?? onPress}
    />
  );
}
export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} />;
}
