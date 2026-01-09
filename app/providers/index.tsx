import { QueryClientProvider } from "./QueryClientProvider"

export const Providers = ({ children }: { children: React.ReactNode }) => {
  return <QueryClientProvider>{children}</QueryClientProvider>
}
