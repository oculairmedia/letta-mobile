import { renderHook, waitFor } from "@testing-library/react-native"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { useAgents } from "../use-agents"
import { useLettaClient } from "@/providers/LettaProvider"
import { createElement, type ReactNode } from "react"

// Mock the LettaProvider
jest.mock("@/providers/LettaProvider", () => ({
  useLettaClient: jest.fn(),
}))

const mockUseLettaClient = useLettaClient as jest.MockedFunction<typeof useLettaClient>

describe("useAgents", () => {
  let queryClient: QueryClient

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
      },
    })
    jest.clearAllMocks()
  })

  afterEach(() => {
    queryClient.clear()
  })

  it("should call agents.list with include parameter for blocks, tools, and tags", async () => {
    const mockList = jest.fn().mockResolvedValue({
      getPaginatedItems: () => [],
    })

    mockUseLettaClient.mockReturnValue({
      lettaClient: {
        agents: {
          list: mockList,
        },
      },
    } as any)

    renderHook(() => useAgents(), { wrapper: createWrapper() })

    await waitFor(() => {
      expect(mockList).toHaveBeenCalledWith({
        include: ["agent.blocks", "agent.tools", "agent.tags"],
      })
    })
  })

  it("should return agents sorted by created_at descending", async () => {
    const mockAgents = [
      { id: "1", name: "Agent 1", created_at: "2024-01-01T00:00:00Z" },
      { id: "2", name: "Agent 2", created_at: "2024-01-03T00:00:00Z" },
      { id: "3", name: "Agent 3", created_at: "2024-01-02T00:00:00Z" },
    ]

    const mockList = jest.fn().mockResolvedValue({
      getPaginatedItems: () => mockAgents,
    })

    mockUseLettaClient.mockReturnValue({
      lettaClient: {
        agents: {
          list: mockList,
        },
      },
    } as any)

    const { result } = renderHook(() => useAgents(), { wrapper: createWrapper() })

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    // Should be sorted newest first
    expect(result.current.data?.[0].id).toBe("2")
    expect(result.current.data?.[1].id).toBe("3")
    expect(result.current.data?.[2].id).toBe("1")
  })

  it("should not fetch when lettaClient is not available", async () => {
    mockUseLettaClient.mockReturnValue({
      lettaClient: null,
    } as any)

    const { result } = renderHook(() => useAgents(), { wrapper: createWrapper() })

    // Query should not be enabled
    expect(result.current.isFetching).toBe(false)
    expect(result.current.data).toBeUndefined()
  })
})
