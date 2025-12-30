/**
 * React Error Boundary Component
 * Catches JavaScript errors anywhere in the child component tree and displays a fallback UI
 */
import { Component, ErrorInfo, ReactNode } from 'react';
import { Result, Button } from 'antd';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
}

class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
    };
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    // Update state so the next render will show the fallback UI
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    // Log error details for debugging
    console.error('ErrorBoundary caught an error:', error, errorInfo);
    this.setState({
      error,
      errorInfo,
    });
  }

  handleReset = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
    });
  };

  render() {
    if (this.state.hasError) {
      // Custom fallback UI if provided
      if (this.props.fallback) {
        return this.props.fallback;
      }

      // Default fallback UI
      return (
        <Result
          status="error"
          title="Something went wrong"
          subTitle={
            <>
              <p>An unexpected error occurred while rendering this component.</p>
              {this.state.error && (
                <details style={{ marginTop: 16, textAlign: 'left' }}>
                  <summary style={{ cursor: 'pointer', fontWeight: 'bold' }}>
                    Error Details
                  </summary>
                  <pre style={{ 
                    marginTop: 8, 
                    padding: 12, 
                    background: '#f5f5f5', 
                    borderRadius: 4,
                    overflow: 'auto',
                    fontSize: 12,
                  }}>
                    {this.state.error.toString()}
                    {this.state.errorInfo && (
                      <>
                        {'\n\n'}
                        {this.state.errorInfo.componentStack}
                      </>
                    )}
                  </pre>
                </details>
              )}
            </>
          }
          extra={[
            <Button type="primary" key="reset" onClick={this.handleReset}>
              Try Again
            </Button>,
            <Button key="reload" onClick={() => window.location.reload()}>
              Reload Page
            </Button>,
          ]}
        />
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;

