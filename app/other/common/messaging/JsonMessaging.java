/*
 * Copyright 2012-2014 Nikolay A. Viguro
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package other.common.messaging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rabbitmq.client.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import play.Play;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Prototype for JSON message broadcasting.
 *
 * @author Nikolay A. Viguro, Tommi S.E. Laukkanen
 */

public class JsonMessaging
{

	private static final Logger LOGGER = LogManager.getLogger(JsonMessaging.class);
	/**
	 * The instance ID.
	 */
	private final UUID instanceId;
	/**
	 * The subjects that has been registered to receive JSON encoded messages.
	 */
	private final Set<String> jsonSubjects = Collections.synchronizedSet(new HashSet<String>());
	/**
	 * The receive queue for JSON objects.
	 */
	private final BlockingQueue<JsonEnvelope> jsonReceiveQueue = new ArrayBlockingQueue<>(100);
	private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
	private UUID sender;
	/**
	 * The AMQ connection.
	 */
	private com.rabbitmq.client.Connection connection;
	/**
	 * Boolean flag reflecting whether threads should be close.
	 */
	private boolean shutdownThreads = false;
	/**
	 * The JSON broadcast listen thread.
	 */
	private Thread jsonBroadcastListenThread;

	private Channel channel;

	public JsonMessaging(final UUID instanceId)
	{
		// Create a ConnectionFactory
		ConnectionFactory connectionFactory = new ConnectionFactory();

		this.instanceId = instanceId;

		// Create a Connection
		try
		{
			// Create a ConnectionFactory
			connectionFactory.setHost(Play.configuration.getProperty("AMQPhost"));
			connectionFactory.setPort(Integer.valueOf(Play.configuration.getProperty("AMQPport")));

			connection = connectionFactory.newConnection();
			channel = connection.createChannel();

			// Create exchange
			channel.exchangeDeclare("iris", "topic", true);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	/**
	 * Starts the messaging to listen for JSON objects.
	 */
	public void start()
	{
		// Startup listen thread.
		jsonBroadcastListenThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				listenBroadcasts();
			}
		}, "json-broascast-listen");
		jsonBroadcastListenThread.setName("JSON Messaging Listen Thread");
		jsonBroadcastListenThread.start();

		// Add close hook to close the listen thread when JVM exits.
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			public void run()
			{
				shutdownThreads = true;
				jsonBroadcastListenThread.interrupt();
				try
				{
					jsonBroadcastListenThread.join();
				}
				catch (final InterruptedException e)
				{
					LOGGER.debug(e.toString());
				}
			}
		});
	}

	/**
	 * Closes connection to message broker.
	 */
	public void close()
	{
		try
		{
			channel.close();
			connection.close();
			shutdownThreads = true;

			if (jsonBroadcastListenThread != null)
			{
				jsonBroadcastListenThread.interrupt();
				jsonBroadcastListenThread.join();
			}
		}
		catch (final Exception e)
		{
			LOGGER.error("Error shutting down JsonMessaging.", e);
		}
	}

	/**
	 * Gets the JSON message received to subject or null if nothing has been received
	 *
	 * @return the JSON message or null
	 */
	public JsonEnvelope getJsonObject()
	{
		synchronized (jsonReceiveQueue)
		{
			if (jsonReceiveQueue.size() > 0)
			{
				return jsonReceiveQueue.poll();
			}
		}
		return null;
	}

	/**
	 * Checks whether JSON message has been received.
	 *
	 * @return true if JSON message is available
	 */
	public int hasJsonObject()
	{
		synchronized (jsonReceiveQueue)
		{
			return jsonReceiveQueue.size();
		}
	}

	/**
	 * Sends object as JSON encoded message with given subject.
	 *
	 * @param subject the subject
	 * @param object  the object
	 */
	public void broadcast(String subject, Object object)
	{
		String className = object.getClass().getName();
		String jsonString = gson.toJson(object);

		try
		{
			String callbackQueueName = channel.queueDeclare().getQueue();

			// Create a message headers
			Map<String, Object> headers = new HashMap<>();
			headers.put("sender", instanceId.toString());
			headers.put("class", className);

			// Publish message to topic
			channel.basicPublish(
					"iris",
					subject,
					new AMQP.BasicProperties.Builder()
							.headers(headers)
							.replyTo(callbackQueueName)
							.build(),
					jsonString.getBytes()
			);
		}
		catch (IOException e)
		{
			LOGGER.debug("Error sending JSON message: " + object + " to subject: " + subject, e);
		}
	}

	/**
	 * Blocking receive to listen for JOSN messages arriving to given topic.
	 *
	 * @return the JSON message
	 */
	public JsonEnvelope receive() throws InterruptedException
	{
		return jsonReceiveQueue.take();
	}

	/**
	 * Blocking receive to listen for JOSN messages arriving to given topic.
	 *
	 * @return the JSON message
	 */
	public JsonEnvelope receive(final int timeoutMillis) throws InterruptedException
	{
		return jsonReceiveQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Method for receiving subscribing to listen JSON objects on a subject.
	 *
	 * @param subject the subject
	 */
	public void subscribe(String subject)
	{
		jsonSubjects.add(subject);
	}

	private void listenBroadcasts()
	{
		try
		{
			String queueName = channel.queueDeclare().getQueue();

			for (String subject : jsonSubjects)
			{
				channel.queueBind(queueName, "iris", subject);
			}

			QueueingConsumer consumer = new QueueingConsumer(channel);
			channel.basicConsume(queueName, true, consumer);

			while (!shutdownThreads)
			{
				QueueingConsumer.Delivery delivery = consumer.nextDelivery();

				// Get headers
				Map<String, Object> headers = delivery.getProperties().getHeaders();

				String message = new String(delivery.getBody());
				String subject = delivery.getEnvelope().getRoutingKey();
				String corrID = delivery.getProperties().getCorrelationId();
				String replyTo = delivery.getProperties().getReplyTo();
				String className = new String(((LongString) headers.get("class")).getBytes());
				String senderName = new String(((LongString) headers.get("sender")).getBytes());

				Class<?> clazz = Class.forName(className);
				Object object = gson.fromJson(message, clazz);

				JsonEnvelope envelope = new JsonEnvelope(
						UUID.fromString(senderName),
						replyTo,
						corrID,
						subject,
						object);

				LOGGER.debug("Received message with ID: " + delivery.getProperties().getMessageId()
						+ " with correlation ID: " + corrID
						+ " sender: " + envelope.getSenderInstance()
						+ " to subject: "
						+ envelope.getSubject() + " (" + envelope.getClass().getSimpleName() + ")");

				jsonReceiveQueue.put(envelope);

				// debug
				if (jsonReceiveQueue.size() > 10)
				{
					LOGGER.info("Queue is too big! " + jsonReceiveQueue.size());
				}
			}
		}
		catch (final ClassNotFoundException e)
			{
				LOGGER.debug("Error deserializing JSON message.", e);
			}
		catch (InterruptedException e)
			{
				LOGGER.debug("Error JSON message.", e);
			}
		catch (IOException e)
			{
				e.printStackTrace();
			}

	}
}