package com.hl.socket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Resource;
import javax.servlet.ServletContext;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.socket.TextMessage;

import com.alibaba.fastjson.JSON;
import com.hl.dao.RedisDao;
import com.hl.service.InvoiceService;
import com.hl.util.Const;
import com.hl.util.ImageUtil;
import com.hl.util.MessageUtil;
import com.hl.websocket.SystemWebSocketHandler;
import com.sun.org.apache.bcel.internal.generic.NEW;

public class SwitcherThread implements Runnable {

	private ServletContext servletContext;

	private RedisDao redisDao;
	private InvoiceService invoiceService;
	
	private SystemWebSocketHandler systemWebSocketHandler;

	private SocketLoadTool socketListener;

	private Long wait_size = 0l;
	private Long manage_size = 0l;

	private Socket socket;
	private InputStream inputStream = null;
	private OutputStream outputStream = null;

	private Integer thread_msg = 0;

	// 用于上锁的对象，当前线程在等待队列和操作队列都为空的时候进入等待状态，交给其他请求线程执行
	// 其他线程执行完后，通知该线程继续切换
	public SwitcherThread(Integer thread_msg, ServletContext servletContext) {
		ApplicationContext applicationContext = 
				WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
		this.socketListener = (SocketLoadTool) applicationContext.getBean("socketListener");
		this.redisDao = (RedisDao) applicationContext.getBean("redisDao");
		this.systemWebSocketHandler = (SystemWebSocketHandler) applicationContext.getBean("systemWebSocketHandler");
		this.invoiceService = (InvoiceService) applicationContext.getBean("invoiceService");
		
		this.thread_msg = thread_msg;
		this.servletContext = servletContext;
		this.socket = socketListener.getSocket();
		try {
			if (socket != null) {
				System.out.println("SwitcherThread成功连接到算法服务器");
				outputStream = socket.getOutputStream();
				inputStream = socket.getInputStream();
			} else {
				System.out.println("SwitcherThread成功连接到算法服务器失败");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		servletContext.setAttribute(Const.THREAD_MSG, thread_msg);// 将这个变量存到servletContext中
	}

	@Override
	public void run() {
		System.out.println("新建SwitcherThread，开始执行");
		try {
			synchronized (thread_msg) {
				while (true) {
					wait_size = redisDao.getWaitSize();
					manage_size = redisDao.getManageSize();
					if (wait_size == 0l && manage_size == 0l) {
						System.out.println("SwitcherThread睡眠,等待新请求加入两个队列");
						thread_msg.wait();
						System.out.println("SwitcherThread被唤醒");
					}
					// 重新读
					wait_size = redisDao.getWaitSize();
					manage_size = redisDao.getManageSize();
					if (manage_size != 0l) {
						switchManageModel();
					}
					// 重新读
					wait_size = redisDao.getWaitSize();
					manage_size = redisDao.getManageSize();
					if (wait_size != 0l && manage_size == 0) {
						switchRecognizeInvoice();
					}
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void switchRecognizeInvoice() {
		// 0.延时5秒
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		// 1.先得到等待队列头的action_id
		String action_id = redisDao.getRight(Const.RECOGNIZE_WAIT, 0l);
		// 2.根据这个作为key，取得对应的url
		String url = (String) redisDao.getValue(action_id);
		//补充，将当前要跑的发票的url等信息发给前端
		invoiceService.broadcastNextRecognize(new Integer(action_id), url);
		// 3.将网络url转换为本地硬盘路径
		String absolute_path = ImageUtil.urlToLocalPathBmp(url, "E:/invoice/originImage/");
		// 4.补充协议json信息
		Map<String, Object> msg_map = new HashMap<>();
		msg_map.put(Const.URL, absolute_path);
		// 5.发送消息
		MessageUtil.sendMessage(outputStream, 1, JSON.toJSONString(msg_map),systemWebSocketHandler);
		System.out.println(action_id + "发送了识别请求");
		//6. 调用service层方法处理识别过程返回的数据
		invoiceService.broadcastRecognizeProcess(inputStream,new Integer(action_id),url);
	}

	public void switchManageModel() {
		
		// 1.先得到等待队列头的action_id
		String action_id = redisDao.getRight(Const.MANAGE_WAIT,0l);
		// 2.根据这个作为key，取得对应的url,json_model,msg_id
		String manage_map_str = (String) redisDao.getValue(action_id);
	    Map<String, Object>manage_map = JSON.parseObject(manage_map_str);
	    //4.判断msg_id，决定采取的操作类型
	    int msg_id = (int) manage_map.get(Const.MSG_ID);
	    switch (msg_id) {
		case 2://增加
		{
			//首先，将网络url变更为本地url
			String url = (String) manage_map.get(Const.URL);
			String absulote_path = ImageUtil.urlToLocalPathBmp(url,"E:/invoice/originImage/");
			//得到json_model，加入图片url
			Map<String, Object>json_model_map = (Map<String, Object>) manage_map.get(Const.JSON_MODEL);
			json_model_map.put(Const.URL, absulote_path);
			//发送消息
			//另外json_model还要包一层。。
			Map<String, Object>temp = new HashMap<>();
			temp.put(Const.JSON_MODEL, json_model_map);
			MessageUtil.sendMessage(outputStream, 2, JSON.toJSONString(temp),systemWebSocketHandler);
			System.out.println(action_id + "发送了新增发票类型请求");
			//调用service层的方法处理增加模板的结果
			invoiceService.broadcastAddNewModel(inputStream,new Integer(action_id),json_model_map,url);
		}
		break;
		
		case 3://删除
		{
			int model_id = (int) manage_map.get(Const.MODEL_ID);
			//另外还要包一层。。
			Map<String, Object>temp = new HashMap<>();
			temp.put("id", model_id);
			//System.out.println("要删除的model_id为" + model_id);
			System.out.println(JSON.toJSONString(temp));
			MessageUtil.sendMessage(outputStream, 3, JSON.toJSONString(temp),systemWebSocketHandler);
			System.out.println(action_id + "发送了删除发票模板请求");
			//调用service层的方法处理删除模板的结果
			invoiceService.broadcastDeleteModel(inputStream,new Integer(action_id),model_id);
		}
		break;
		
		case 4: //修改
		{
			//首先，将网络url变更为本地url
			String url = (String) manage_map.get(Const.URL);
			String absulote_path = ImageUtil.urlToLocalPathBmp(url,"E:/invoice/originImage/");
			//得到model_id
			int model_id = (int) manage_map.get(Const.MODEL_ID);
			//发送消息
			//得到json_model
			Map<String, Object>json_model_map = (Map<String, Object>) manage_map.get(Const.JSON_MODEL);
			//将模板id和图片url放到josn_model里
			json_model_map.put("id",model_id);
			json_model_map.put(Const.URL, absulote_path);
			//另外json_model还要包一层。。
			Map<String, Object>temp = new HashMap<>();
			temp.put(Const.JSON_MODEL, json_model_map);
			MessageUtil.sendMessage(outputStream, 4, JSON.toJSONString(temp),systemWebSocketHandler);
			System.out.println(action_id + "发送了修改发票模板请求");
			invoiceService.broadcastUpdateModel(inputStream,new Integer(action_id),json_model_map,url,model_id);
		}
		break;
		
		case 5://清空
			MessageUtil.sendMessage(outputStream, 5, null, systemWebSocketHandler);
			System.out.println(action_id + "发送了清空发票模板请求");
			invoiceService.broadcastClearModel(inputStream,new Integer(action_id));
			break;
			
		default:
			break;
		}
	    
	}
	
	
}
