import { LettaClientProvider } from "./LettaProvider"
import { QueryClientProvider } from "./QueryClientProvider"

export const Providers = ({ children }: { children: React.ReactNode }) => {
  return (
    <QueryClientProvider>
      <LettaClientProvider>{children}</LettaClientProvider>
    </QueryClientProvider>
  )
}
