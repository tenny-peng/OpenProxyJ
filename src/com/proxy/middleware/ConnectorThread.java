package com.proxy.middleware;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import com.alibaba.fastjson.util.IOUtils;
import com.proxy.common.Constant;
import com.proxy.common.DataFilter;
import com.proxy.common.OnBrokenCallBackInconnctor;
import com.proxy.domain.ForWard;
import com.proxy.utils.Log;

/**
 * 连接件，把来自浏览器等被代理端的请求盲转发
 */
public class ConnectorThread extends Thread {

	private OnBrokenCallBackInconnctor onBrokenCallBackInconnctor;// 处理异常回调
	private Socket localSocket;// 接受本地浏览器代理输入Socket
	private InputStream proxyedInputStream;// 被代理的输入数据(把这个输入流的东西转发到代理)
	private OutputStream proxyedOutputStream;// 被代理的输出流(把代理服务器的返回写入到被代理的服务器)

	private ForWard proxedServerForward;// 目标代理服务器
	private Thread readDataFromProxyServerThread;// 从代理服务器读取数据线程
	private Socket connectProxyServerSocket;// 链接到远程代理服务器的Socket

	private OutputStream proxyServerOutputStream;// 代理服务器的输出流(把浏览器等被代理端的数据写入这个流)
	private InputStream proxyServerInputStream;// 代理服务器的输入流(把这个流数写给浏览器)

	private DataFilter dataFilter;
//	private boolean readBanner = false;

	public ConnectorThread(Socket socket, ForWard forward, OnBrokenCallBackInconnctor onBrokenCallBackInconnctor) {
		this.localSocket = socket;
		this.onBrokenCallBackInconnctor = onBrokenCallBackInconnctor;
		this.proxedServerForward = forward;
		this.dataFilter = new DataFilter();

		try {
			this.proxyedInputStream = this.localSocket.getInputStream();
			this.proxyedOutputStream = this.localSocket.getOutputStream();
			// 链接代理服务器
			this.connectProxyServerSocket = new Socket(proxedServerForward.getHost(), proxedServerForward.getPort());
			this.proxyServerOutputStream = this.connectProxyServerSocket.getOutputStream();
			this.proxyServerInputStream = this.connectProxyServerSocket.getInputStream();
			this.readDataFromProxyServerThread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						byte[] proxedBuffer = new byte[Constant.BUFFER_SIZE];
						int ret = -1;
						// 读取代理服务器的数据返回给浏览器
						while ((ret = proxyServerInputStream.read(proxedBuffer)) != -1) {
							if (null != ConnectorThread.this.dataFilter) {
								ConnectorThread.this.dataFilter.decodeData(proxedBuffer, 0, ret);// 解密
							}
							proxyedOutputStream.write(proxedBuffer, 0, ret);// 发送给浏览器
							proxyedOutputStream.flush();
						}
					} catch (IOException e) {
						closeConnect();
						Log.redPrint("数据读取异常:" + forward.toString() + "==>" + e.getLocalizedMessage());
					} finally {
						if (null != onBrokenCallBackInconnctor) {
							onBrokenCallBackInconnctor.onBroken(new Exception("在ConnectorThread中通信结束"), "Connector.Run",
									ConnectorThread.this);
						}
					}

				}
			});
			this.readDataFromProxyServerThread.start();
		} catch (IOException e) {
			closeConnect();
			Log.redPrint("本地转发器错误:" + e.getLocalizedMessage());
		} finally {
			if (null != onBrokenCallBackInconnctor) {
				onBrokenCallBackInconnctor.onBroken(new Exception("在ConnectorThread构造中通信结束"), "Connector.Run",
						ConnectorThread.this);
			}
		}
		start();
	}

	@Override
	public void run() {
		if (null != proxyedInputStream) {
			try {
				int readSize = -1;
				// 读取浏览器数据，发送给代理服务器
				byte[] buffer = new byte[Constant.BUFFER_SIZE];
				while ((readSize = proxyedInputStream.read(buffer)) != -1) {
					if (null == this.proxyServerOutputStream) {
						break;
					}
					if (null != dataFilter) {// 加密
						dataFilter.encodeData(buffer, 0, readSize);
					}
					this.proxyServerOutputStream.write(buffer, 0, readSize);
					this.proxyServerOutputStream.flush();
				}
			} catch (IOException e) {
				Log.redPrint("本地转发器异常结束:" + e.getLocalizedMessage());
			} finally {
				closeConnect();
				if (null != onBrokenCallBackInconnctor) {
					onBrokenCallBackInconnctor.onBroken(new Exception("正常数据连接结束"), "Connector.Run",
							ConnectorThread.this);
				}
			}

		}
	}

	public void closeConnect() {
			IOUtils.close(proxyedInputStream);
			IOUtils.close(proxyedOutputStream);
			IOUtils.close(proxyServerInputStream);
			IOUtils.close(proxyServerOutputStream);
			IOUtils.close(localSocket);
	}

}
