from gensim import corpora
from gensim.summarization.bm25 import BM25
import json
import random
import numpy as np
from sklearn.cluster import KMeans
import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import AutoTokenizer, AutoModel, RobertaModel
from tqdm import tqdm
import re
import heapq
import jsonlines

cache_dir = './Models'

#FIXME: line number

def camel_case_split(identifier) -> list:
    words = re.findall(r'[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)', identifier)
    return words, " ".join(words)

class Embedding(nn.Module):   
    def __init__(self, encoder):
        super(Embedding, self).__init__()
        self.encoder = encoder
      
    def forward(self, code_inputs=None, nl_inputs=None): 
        if code_inputs is not None:
            outputs = self.encoder(code_inputs,attention_mask=code_inputs.ne(1))[0]
            outputs = (outputs*code_inputs.ne(1)[:,:,None]).sum(1)/code_inputs.ne(1).sum(-1)[:,None]
            return torch.nn.functional.normalize(outputs, p=2, dim=1)
        else:
            outputs = self.encoder(nl_inputs,attention_mask=nl_inputs.ne(1))[0]
            outputs = (outputs*nl_inputs.ne(1)[:,:,None]).sum(1)/nl_inputs.ne(1).sum(-1)[:,None]
            return torch.nn.functional.normalize(outputs, p=2, dim=1)


class InstanceSelection:
    # TODO: determine the data format of the input and output
    def __init__(self, number = 5, key = None):
        self.number = number
        self.key = key

    # code-based instance selection, task-level
    def random_selection(self, train, test):
        random.shuffle(train)
        selected = random.sample(list(range(len(train))), self.number)
        selected_train = [train[i] for i in selected]
        return selected_train

# code-based instance selection, instance-level
    def bm25_selection(self, train, test, camel=True):
        data = []
        for obj in train:
            data.append(obj[self.key].strip())
        corpus = data
        tokenized_corpus = []

        for doc in corpus:
            if camel:
                split_words = []
                for word in doc.split(" "):
                    camel_case_words, _ = camel_case_split(word)
                    split_words.extend(camel_case_words)
                tokenized_corpus.append(split_words)
            else:
                tokenized_corpus.append(doc.split(" "))

        bm25_model = BM25(tokenized_corpus)
        average_idf = sum(map(lambda k: float(bm25_model.idf[k]), bm25_model.idf.keys())) / len(bm25_model.idf.keys())
        results = []

        for obj in tqdm(test):
            query = obj[self.key].split(" ")
            if camel:
                split_query = []
                for word in query:
                    camel_case_words, _ = camel_case_split(word)
                    split_query.extend(camel_case_words)
            else:
                split_query = query

            score = bm25_model.get_scores(split_query)
            rtn = sorted(enumerate(score), key=lambda x: x[1], reverse=True)[:self.number]
            sorted_topk = []
            for i in range(len(rtn)):
                sorted_topk.append(train[rtn[i][0]])
            results.append(sorted_topk)

        return results

    
    # state-based, instance-level
    def state_selection(self, train, test):
        # javaFileName, className, methodName
        pass


    # code-based instance selection, task-level
    def task_kmeans_selection(self, train, test, model_path = "roberta-base'", cluster_number=5):
        tokenizer = AutoTokenizer.from_pretrained(model_path, cache_dir=cache_dir)
        model = RobertaModel.from_pretrained(model_path, cache_dir=cache_dir)
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model = Embedding(model)
        model.to(device)
        code_emb = []
        model.eval()
        for obj in tqdm(train):
            with torch.no_grad():
                code_tokens=tokenizer.tokenize(' '.join(obj['code_tokens']))[:256-4] #FIXME: code_tokens
                tokens=[tokenizer.cls_token,"<encoder-only>",tokenizer.sep_token]+code_tokens+[tokenizer.sep_token]
                tokens_ids=tokenizer.convert_tokens_to_ids(tokens)
                context_embeddings=model(code_inputs=torch.tensor([tokens_ids]).to(device))
                code_emb.append(context_embeddings.cpu().numpy())
        code_emb = np.concatenate(code_emb,0)
        clf = KMeans(n_clusters=cluster_number, init='k-means++') 
        code_label = clf.fit_predict(code_emb)
        class_pos = {}
        selected = []
        for idx in range(len(code_label)):
            i = code_label[idx]
            if i not in class_pos:
                class_pos[i] = [idx]
            else:
                class_pos[i].append(idx)
        for i in class_pos:
            pos = random.randint(0,len(class_pos[i])-1) 
            selected.append(class_pos[i][pos])

        selected_train = [train[i] for i in selected]
        return selected_train

    def unixcoder_selection(self, train, test, model_path):
        tokenizer = AutoTokenizer.from_pretrained(model_path)
        model = RobertaModel.from_pretrained(model_path)
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model = Embedding(model)
        model.to(device)

        code_emb = []
        model.eval()
        for obj in tqdm(train):
            with torch.no_grad():
                code_tokens=tokenizer.tokenize(' '.join(obj[self.key]))[:256-4]
                tokens=[tokenizer.cls_token,"<encoder-only>",tokenizer.sep_token]+code_tokens+[tokenizer.sep_token]
                tokens_ids=tokenizer.convert_tokens_to_ids(tokens)
                context_embeddings=model(code_inputs=torch.tensor([tokens_ids]).to(device))
                code_emb.append(context_embeddings.cpu().numpy())
        code_emb = np.concatenate(code_emb,0)

        test_emb = []
        model.eval()
        for obj in tqdm(test):
            with torch.no_grad():
                code_tokens=tokenizer.tokenize(' '.join(obj[self.key]))[:256-4]
                tokens=[tokenizer.cls_token,"<encoder-only>",tokenizer.sep_token]+code_tokens+[tokenizer.sep_token]
                tokens_ids=tokenizer.convert_tokens_to_ids(tokens)
                context_embeddings=model(code_inputs=torch.tensor([tokens_ids]).to(device))
                test_emb.append(context_embeddings.cpu().numpy())
        test_emb = np.concatenate(test_emb,0)

        processed = []
        for idx in tqdm(range(len(test_emb))):
            query_embeddings = test_emb[idx]
            cos_sim = F.cosine_similarity(torch.Tensor(code_emb), torch.Tensor(query_embeddings), dim=1).cpu().numpy()
            hits = []
            topk = heapq.nlargest(self.number, range(len(cos_sim)), cos_sim.__getitem__)
            for i in topk:
                hits.append({'score':cos_sim[i], 'corpus_id':i})
            
            code_candidates_tokens = []
            for i in range(len(hits)):
                code_candidates_tokens.append({self.key: train[hits[i]['corpus_id']]['self.key'], 'score': float(hits[i]['score']), 'idx':i+1})
            obj = test[idx]
            processed.append({'case': obj, 'code_candidates_tokens': code_candidates_tokens})
        
        return processed


    def instance_kmeans_selection(self, train, test, number):
        pass
        

if __name__ == '__main__':
    # Load the data
    json_path = './data.json'
    with open(json_path, "r") as f:
        data = json.load(f)

    data_list = []
    for filename, content in data.items():
        temp_dict = dict()
        temp_dict['method_name'] = filename
        temp_dict['file_code'] = content['file_code']
        temp_dict['method_code'] = content['method_code']
        data_list.append(temp_dict)


    selector = InstanceSelection(number=5, key='method_code')
    results = selector.bm25_selection(data_list, data_list[:500])
    
    with open('./results.json', 'w') as f:
        json.dump(results, f)



