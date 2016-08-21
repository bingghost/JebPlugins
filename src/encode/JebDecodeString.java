import java.io.*;
import java.util.List;

import jeb.api.IScript;
import jeb.api.JebInstance;
import jeb.api.ui.*;
import jeb.api.ast.*;
import jeb.api.dex.Dex;
import jeb.api.dex.DexClass;
import jeb.api.dex.DexClassData;
import jeb.api.dex.DexMethod;
import jeb.api.dex.DexMethodData;

public class JebDecodeString implements IScript {
	private final static String DecodeMethodSignature = "Lcom/pnfsoftware/jebglobal/Si;->ob([BII)Ljava/lang/String;";
	private final static String DecodeClassSignature = "Lcom/pnfsoftware/jebglobal/Si;";
	private JebInstance mJebInstance = null;
	private Constant.Builder mBuilder = null;

	private static File logFile;
	private static BufferedWriter writer;

	/**
	 * 功能: 遍历所有的类 找到指定的类
	 * 
	 * @return 指定类的dex索引, 没有找到返回-1
	 */
	@SuppressWarnings("unchecked")
	private int findClass(Dex dex, String findClassSignature) {
		List<String> listClassSignatures = dex.getClassSignatures(false);
		int index = 0;
		for (String classSignatures : listClassSignatures) {
			if (classSignatures.equals(findClassSignature)) {
				mJebInstance.print("find:" + classSignatures);
				return index;
			}
			index++;
		}

		return -1;
	}

	private int findMethod(Dex dex, int classIndex, String findMethodSignature) {
		DexClass dexClass = dex.getClass(classIndex);
		DexClassData dexClassData = dexClass.getData();
		DexMethodData[] dexMethods = dexClassData.getDirectMethods();
		for (int i = 0; i < dexMethods.length; i++) {
			int methodIndex = dexMethods[i].getMethodIndex();
			DexMethod dexMethod = dex.getMethod(methodIndex);
			String methodSignature = dexMethod.getSignature(true);

			if (methodSignature.equals(findMethodSignature)) {
				mJebInstance.print("find:" + methodSignature);
				return methodIndex;
			}
		}

		return -1;
	}
	
	/***
	 * 功能: 遍历指定函数的应用方法
	 * @param dex
	 * @param methodIndex
	 */
	@SuppressWarnings("unchecked")
	private void traverseReferences(Dex dex,int methodIndex) {
		List<Integer> methodReferences = dex.getMethodReferences(methodIndex);
		mJebInstance.print("引用数量:" + methodReferences.size());

		for (Integer refIndex : methodReferences) {
			DexMethod refDexMethod = dex.getMethod(refIndex);
			mJebInstance.print("引用的方法：" + refDexMethod.getSignature(true));

			// 找到AST中对应的Method
			mJebInstance.decompileMethod(refDexMethod.getSignature(true));
			Method decompileMethodTree = mJebInstance.getDecompiledMethodTree(refDexMethod.getSignature(true));

			// 拿到语句块，遍历所有语句
			List<IElement> subElements = decompileMethodTree.getSubElements();
			replaceDecodeMethod(subElements, decompileMethodTree);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run(JebInstance jebInstance) {
		// 初始化相关信息
		jebInstance.print("start decode strings plugin");
		init(jebInstance, "D:\\log.txt");
		mBuilder = new Constant.Builder(jebInstance);
		JebUI ui = jebInstance.getUI();
		JavaView javaView = (JavaView) ui.getView(View.Type.JAVA);
		Dex dex = jebInstance.getDex();

		while (true) {
			int classIndex = findClass(dex, DecodeClassSignature);
			if (classIndex == -1) {
				break;
			}

			int methodIndex = findMethod(dex, classIndex, DecodeMethodSignature);
			if (methodIndex == -1) {
				break;
			}
			
			traverseReferences(dex,methodIndex);
			
			// 刷新UI
			javaView.refresh();
			break;
		}

		// 关闭文件
		close();
	}
	
    private void replaceDecodeMethod(List<IElement> elements, IElement parentEle) {
        for (IElement element : elements) {
        	
        	if (!(element instanceof Call)) {
        		// 不是方法
                List<IElement> subElements = element.getSubElements();
                replaceDecodeMethod(subElements, element);
        		continue;
        	}
        	
            Call call = (Call) element;
            Method method = call.getMethod();
            if (!method.getSignature().equals(DecodeMethodSignature)) {
            	// 不是指定函数签名
                List<IElement> subElements = element.getSubElements();
                replaceDecodeMethod(subElements, element);
            	continue;
            }
            
            
            analyzeArguments(call,parentEle,element);
            
        }
    }
    
    // 解析引用函数的参数
    private void analyzeArguments(Call call,IElement parentEle,IElement element) {
        try {
        	// 拿到函数的参数
            List<IExpression> arguments = call.getArguments();
            
            // 获取第一个参数元素
            NewArray arg1 = (NewArray) arguments.get(0);
            List encBL = arg1.getInitialValues();
            if (encBL == null) {
                return;
            }
            
            int size = encBL.size();
            byte[] enStrBytes = new byte[size];
            int decFlag;
            int encode;
            int i = 0;
            
            // 设置Flags 有的地方可能是变量形式的参数
            if (arguments.get(1) instanceof Constant) {
                decFlag = ((Constant) (arguments.get(1))).getInt();
            } else {
                decFlag = 4;
            }
            
            // 初始化加密字节数组
            for (i = 0; i < size; i++) {
                enStrBytes[i] = ((Constant) encBL.get(i)).getByte();
            }
            
            // 设置encode
            encode = ((Constant) (arguments.get(2))).getInt();
            
            String decString = do_dec(enStrBytes,decFlag,encode);
            logWrite("解密后字符串： " + decString);
            // mJebInstance.print("解密后字符串： " + decString);
            
            // 替换原来的表达式
            parentEle.replaceSubElement(element, mBuilder.buildString(decString));
        } catch (Exception e) {
            mJebInstance.print(e.toString());
        }
    }
    
    // 根据情况解密字符串
    private String do_dec(byte[] enStrBytes, int decFlag, int encode) {
        String dec = "";
        
        while (true) {
            if (decFlag != 4) {
                dec = decString(enStrBytes, decFlag, encode);
                break;
            }                   
            
            // 穷举可能存在的情况 0 1 2
            dec = decString(enStrBytes, 2, encode);
            if (!isStr(dec)) {
                dec = decString(enStrBytes, 1, encode);
            }
            
            if (!isStr(dec)) {
                dec = decString(enStrBytes, 0, encode);
            }
        	break;
        }

        return dec;
    }
    
    // 判断字符串是否是一个合理的字符串
    private boolean isStr(String s) {
        int len = s.length() > 3 ? 3 : s.length();
        String str = s.substring(0, len);
        if (str.matches("[a-zA-Z0-9_\u4e00-\u9fa5]*")) {
            return true;
        }
        return false;
    }
    
    private String setString(byte[] bytes_str) {
    	String new_str;
    	
        try {
            new_str = new String(bytes_str, "UTF-8");
        }
        catch(Exception e) {
            new_str = new String(bytes_str);
        }

        return new_str;
    }
    
    // 解密字符串
    public String decString(byte[] enStrBytes, int decFlag, int encode) {
        byte[] decstrArray;
        int enstrLen;

        if(enStrBytes == null) {
            return "decode error";
        }
        
        if (decFlag == 0 || enStrBytes.length == 0) {
        	return setString(enStrBytes);
        }
        
        if(decFlag == 1) {
            enstrLen = enStrBytes.length;
            decstrArray = new byte[enstrLen];
            byte bEncode = ((byte)encode);
            
            for (int i = 0;i < enstrLen;i++) {
            	decstrArray[i] = ((byte)(bEncode ^ enStrBytes[i]));
            	bEncode = decstrArray[i];
            }

            return setString(decstrArray);
        }
        
        if(decFlag == 2) {
            enstrLen = enStrBytes.length;
            decstrArray = new byte[enstrLen];
            String coprightString = "Copyright (c) 1993, 2015, Oracle and/or its affiliates. All rights reserved. ";
            int index = 0;
            for (int i = 0;i < enstrLen;i++) {
            	decstrArray[i] = ((byte)(enStrBytes[i] ^ (((byte)coprightString.charAt(index)))));
                index = (index + 1) % coprightString.length();
            }

            return setString(decstrArray);
        }
        
        return "decode error";
    }
	

	public void logWrite(String log) {
		try {
			writer.write(log + "\r\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void init(JebInstance jebInstance, String logPath) {
		mJebInstance = jebInstance;
		logFile = new File(logPath);
		try {
			writer = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(logFile), "utf-8"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void close() {
		try {
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
