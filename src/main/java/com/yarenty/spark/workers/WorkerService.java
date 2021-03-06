package com.yarenty.spark.workers;

import org.apache.log4j.Logger;

import com.yarenty.spark.workers.cuda.kernel.CudaMultiplicityKernel;
import com.yarenty.spark.workers.java.kernel.JavaMultiplicityKernel;

import static scala.collection.JavaConversions.*;

/**
 * Main class responsible for CUDA/CPU calculations.
 * 
 * @author yarenty
 */
public class WorkerService {
	final static Logger LOG = Logger.getLogger(WorkerService.class);

	final private static WorkerService workerService = new WorkerService();

	final private WorkerRequestControl wrc;

	// possible options are: CUDA,CPU or MIXED
	final private WorkerType calculationType = WorkerType.CPU;
	// this is number of processors to use
	final private int processors = 4;
	// this is how many GPU devices are in the box
	final private int gpus = 1;

	private WorkerService() {
		LOG.info("Lets game begin.");
		wrc = new WorkerRequestControl();
		Thread t = new Thread(new InitializationThread());
		t.start();
	}

	public WorkerRequestControl getWRC() {
		return wrc;
	}

	public static WorkerService getInstance() {
		LOG.info("Creating worker service");
		return workerService;
	}

	private class InitializationThread implements Runnable {

		@Override
		public void run() {
			try {
				LOG.info(">New life is started.");

				// TODO: how to get number of processors and number of GPU
				// automatically!!
				WorkerPoolMap.getInstance().initializePools(processors, gpus);
				LOG.info("Pools initialized");

				// wrc.setServiceStart();

				wrc.setAcceptRequests(true);

				wrc.serviceStarted();
			} catch (Exception e) {
				LOG.error("Could not initialize workers!", e);
			}
		}
	}

	public <T extends Number> void prepareStaticData(final T[] arr,
			final String name, final GPUType type) {
		LOG.info("prepare all CUDA");
		WorkerPoolMap.getInstance().getWorkerPool(WorkerType.CUDA)
				.prepareStaticData(arr, name, type);
		LOG.info("prepare all CPU");
		WorkerPoolMap.getInstance().getWorkerPool(WorkerType.CPU)
				.prepareStaticData(arr, name, type);
	}

	public <T extends Number> T[] exampleMultiplication(T[] a, T[] b) {

		LOG.info("Accept requests:" + wrc.getAcceptRequest() + " pending:"
				+ wrc.getPendingRequests().get());
		if (!wrc.getAcceptRequest()) {
			try {
				wrc.checkServiceStart();
			} catch (InterruptedException e) {
				e.printStackTrace();
				LOG.error("interrrupted initialization");
			}
		}

		LOG.info("Work in progress");

		wrc.getPendingRequests().getAndIncrement();

		T[] out = a; // initialization of output

		WorkerPool pool = WorkerPoolMap.getInstance().getWorkerPool(
				WorkerType.CUDA);

		if (!pool.canWork() && calculationType == WorkerType.MIXED) {
			pool = WorkerPoolMap.getInstance().getWorkerPool(WorkerType.CPU);
		}

		if (pool == null || calculationType == WorkerType.CPU) {
			pool = WorkerPoolMap.getInstance().getWorkerPool(WorkerType.CPU);
		}

		LOG.info("POOL in use: " + pool);

		if (pool != null) {
			wrc.markWorkerRun(); // if initialization needed

			Worker worker = pool.getWorker();

			LOG.info("Worker[" + worker.getID() + "]:: " + worker);
			worker.setArray(a, "a", GPUType.GLOBAL_MEMORY);
			worker.setArray(b, "b", GPUType.GLOBAL_MEMORY);
			worker.setArray(out, "c", GPUType.GLOBAL_MEMORY);

			LOG.info("Worker[" + worker.getID() + "]:: Kernel initialized");

			Kernel kernel = null;
			if (pool.getWorkerType() == WorkerType.CPU) {

				kernel = new JavaMultiplicityKernel();
			} else {
				kernel = new CudaMultiplicityKernel();
			}
			worker.setKernel(kernel);

			LOG.info("Worker[" + worker.getID() + "]:: RUN!!");

			worker.runKernel();

			out = worker.getArray("c");
			pool.returnWorker(worker);
		}

		if (wrc.getPendingRequests().getAndDecrement() == 1) {
			wrc.setOkToStop();
			// wrc.setServiceStart();
		}

		return out;
	}

}
