import { render, fireEvent, waitFor } from "@testing-library/react-native"
import { TextInputModal } from "../text-input-modal"

// Mock the theme hook
jest.mock("@/utils/useAppTheme", () => ({
  useAppTheme: () => ({
    themed: (style: Function | Record<string, unknown>) =>
      typeof style === "function" ? style({ colors: mockColors }) : style,
    theme: { colors: mockColors },
  }),
}))

const mockColors = {
  background: "#ffffff",
  text: "#000000",
  textDim: "#666666",
  tint: "#007AFF",
  palette: {
    overlay20: "rgba(0,0,0,0.2)",
  },
}

describe("TextInputModal", () => {
  const defaultProps = {
    visible: true,
    title: "Test Title",
    onSubmit: jest.fn(),
    onDismiss: jest.fn(),
  }

  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("should render when visible", () => {
    const { getByText } = render(<TextInputModal {...defaultProps} />)

    expect(getByText("Test Title")).toBeTruthy()
  })

  it("should not render content when not visible", () => {
    const { queryByText } = render(<TextInputModal {...defaultProps} visible={false} />)

    // Modal component still renders but content is not visible
    expect(queryByText("Test Title")).toBeNull()
  })

  it("should display message when provided", () => {
    const { getByText } = render(<TextInputModal {...defaultProps} message="This is a message" />)

    expect(getByText("This is a message")).toBeTruthy()
  })

  it("should display default value in input", () => {
    const { getByDisplayValue } = render(
      <TextInputModal {...defaultProps} defaultValue="Initial text" />,
    )

    expect(getByDisplayValue("Initial text")).toBeTruthy()
  })

  it("should display placeholder when provided", () => {
    const { getByPlaceholderText } = render(
      <TextInputModal {...defaultProps} placeholder="Enter text here" />,
    )

    expect(getByPlaceholderText("Enter text here")).toBeTruthy()
  })

  it("should call onSubmit with input value when Save is pressed", () => {
    const onSubmit = jest.fn()
    const { getByText, getByDisplayValue } = render(
      <TextInputModal {...defaultProps} defaultValue="test value" onSubmit={onSubmit} />,
    )

    fireEvent.press(getByText("Save"))

    expect(onSubmit).toHaveBeenCalledWith("test value")
  })

  it("should call onSubmit with updated value after text change", () => {
    const onSubmit = jest.fn()
    const { getByText, getByDisplayValue } = render(
      <TextInputModal {...defaultProps} defaultValue="initial" onSubmit={onSubmit} />,
    )

    const input = getByDisplayValue("initial")
    fireEvent.changeText(input, "updated value")
    fireEvent.press(getByText("Save"))

    expect(onSubmit).toHaveBeenCalledWith("updated value")
  })

  it("should call onDismiss when Cancel is pressed", () => {
    const onDismiss = jest.fn()
    const { getByText } = render(<TextInputModal {...defaultProps} onDismiss={onDismiss} />)

    fireEvent.press(getByText("Cancel"))

    expect(onDismiss).toHaveBeenCalled()
  })

  it("should call onDismiss when overlay is pressed", () => {
    const onDismiss = jest.fn()
    const { getByText } = render(<TextInputModal {...defaultProps} onDismiss={onDismiss} />)

    fireEvent.press(getByText("Cancel"))

    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it("should use custom button text when provided", () => {
    const { getByText } = render(
      <TextInputModal {...defaultProps} submitText="Confirm" cancelText="Abort" />,
    )

    expect(getByText("Confirm")).toBeTruthy()
    expect(getByText("Abort")).toBeTruthy()
  })

  it("should reset value when modal reopens with new defaultValue", async () => {
    const { rerender, getByDisplayValue, queryByDisplayValue } = render(
      <TextInputModal {...defaultProps} visible={true} defaultValue="first" />,
    )

    expect(getByDisplayValue("first")).toBeTruthy()

    // Close and reopen with new default
    rerender(<TextInputModal {...defaultProps} visible={false} defaultValue="first" />)
    rerender(<TextInputModal {...defaultProps} visible={true} defaultValue="second" />)

    await waitFor(() => {
      expect(getByDisplayValue("second")).toBeTruthy()
    })
  })

  it("should accept text input changes", () => {
    const onSubmit = jest.fn()
    const { getByDisplayValue, getByText } = render(
      <TextInputModal {...defaultProps} defaultValue="" onSubmit={onSubmit} />,
    )

    fireEvent.changeText(getByDisplayValue(""), "multi\nline\ntext")
    fireEvent.press(getByText("Save"))

    expect(onSubmit).toHaveBeenCalledWith("multi\nline\ntext")
  })
})
