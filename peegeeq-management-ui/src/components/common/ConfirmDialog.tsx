/**
 * ConfirmDialog Component - Confirmation modal for destructive operations
 */
import React from 'react';
import { Modal } from 'antd';
import { ExclamationCircleOutlined } from '@ant-design/icons';

export interface ConfirmDialogOptions {
  title: string;
  content: React.ReactNode;
  okText?: string;
  cancelText?: string;
  okType?: 'primary' | 'danger';
  onOk: () => void | Promise<void>;
  onCancel?: () => void;
}

export const showConfirmDialog = ({
  title,
  content,
  okText = 'OK',
  cancelText = 'Cancel',
  okType = 'primary',
  onOk,
  onCancel,
}: ConfirmDialogOptions) => {
  Modal.confirm({
    title,
    icon: <ExclamationCircleOutlined />,
    content,
    okText,
    cancelText,
    okType,
    onOk,
    onCancel,
  });
};

export const showDeleteConfirm = (options: Omit<ConfirmDialogOptions, 'okType'>) => {
  showConfirmDialog({
    ...options,
    okType: 'danger',
    okText: options.okText || 'Delete',
  });
};

export const showPurgeConfirm = (queueName: string, onOk: () => void | Promise<void>) => {
  showDeleteConfirm({
    title: `Purge Queue "${queueName}"?`,
    content: (
      <div>
        <p>This will permanently delete all messages in the queue.</p>
        <p><strong>This action cannot be undone.</strong></p>
      </div>
    ),
    okText: 'Purge',
    onOk,
  });
};

export const showDeleteQueueConfirm = (queueName: string, onOk: () => void | Promise<void>) => {
  showDeleteConfirm({
    title: `Delete Queue "${queueName}"?`,
    content: (
      <div>
        <p>This will permanently delete the queue and all its messages.</p>
        <p><strong>This action cannot be undone.</strong></p>
      </div>
    ),
    okText: 'Delete Queue',
    onOk,
  });
};

export default showConfirmDialog;
