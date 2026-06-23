import React from 'react'
import { Card, Table, Button, Tag, Typography, Space, Empty, Modal } from 'antd'
import {
    CheckOutlined,
    ClearOutlined,
    ExclamationCircleOutlined
} from '@ant-design/icons'
import dayjs from 'dayjs'

import { useManagementStore } from '../stores/managementStore'

const { Title } = Typography

/**
 * Notifications page (`/notifications`) — full history view of the in-memory
 * notification feed. The header bell drawer remains the quick-glance surface;
 * this page reads the same `managementStore` slice and lists every entry with
 * its read status. Columns map 1:1 to the `ManagementNotification` fields.
 */
const NotificationsPage: React.FC = () => {
    const { notifications, markAllNotificationsRead, clearNotifications } = useManagementStore()

    const handleClearAll = () => {
        Modal.confirm({
            title: 'Clear all notifications',
            icon: <ExclamationCircleOutlined />,
            content: 'This removes every notification from the list. This cannot be undone.',
            okText: 'Clear All',
            okType: 'danger',
            onOk: () => clearNotifications(),
        })
    }

    const columns = [
        {
            title: 'Timestamp',
            dataIndex: 'timestamp',
            key: 'timestamp',
            render: (ts: string) => dayjs(ts).format('YYYY-MM-DD HH:mm:ss'),
        },
        {
            title: 'Action',
            dataIndex: 'action',
            key: 'action',
        },
        {
            title: 'Resource',
            dataIndex: 'resource',
            key: 'resource',
        },
        {
            title: 'Status',
            dataIndex: 'read',
            key: 'read',
            render: (read: boolean) => (
                <Tag color={read ? 'default' : 'blue'}>{read ? 'Read' : 'New'}</Tag>
            ),
        },
    ]

    return (
        <div className="fade-in">
            <Title level={1}>Notifications</Title>
            <Card
                title="Notification History"
                extra={
                    <Space>
                        <Button
                            icon={<CheckOutlined />}
                            data-testid="mark-all-read-btn"
                            onClick={markAllNotificationsRead}
                            disabled={notifications.length === 0}
                        >
                            Mark All Read
                        </Button>
                        <Button
                            danger
                            icon={<ClearOutlined />}
                            data-testid="clear-all-btn"
                            onClick={handleClearAll}
                            disabled={notifications.length === 0}
                        >
                            Clear All
                        </Button>
                    </Space>
                }
            >
                {notifications.length === 0
                    ? (
                        <div data-testid="notifications-empty">
                            <Empty description="No notifications yet" />
                        </div>
                    )
                    : (
                        <Table
                            data-testid="notifications-table"
                            rowKey="id"
                            columns={columns}
                            dataSource={notifications}
                            pagination={{
                                pageSize: 20,
                                showSizeChanger: true,
                                showTotal: (total) => `${total} notification${total === 1 ? '' : 's'}`,
                            }}
                        />
                    )}
            </Card>
        </div>
    )
}

export default NotificationsPage
